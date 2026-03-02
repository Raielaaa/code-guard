package com.repo.guard.service.gitlab;

import com.fasterxml.jackson.databind.JsonNode;
import com.repo.guard.dto.RepoIngestionRequestDto;
import com.repo.guard.model.repo.CodeChunk;
import com.repo.guard.model.repo.CodeChunkRepository;
import com.repo.guard.service.repo.RepoIngestionWorkerService;
import lombok.extern.slf4j.Slf4j;
import org.gitlab4j.api.GitLabApi;
import org.gitlab4j.api.models.Diff;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.core.io.Resource;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Slf4j
public abstract class AbstractGitLabEventHandler implements GitlabEventHandler {
    protected final ChatModel chatModel;
    protected final CodeChunkRepository chunkRepository;
    protected final RepoIngestionWorkerService ingestionService;
    protected final EmbeddingModel embeddingModel;
    protected final String gitlabUrl;
    protected final String gitlabToken;
    protected final Resource codeReviewPromptResource;

    //  instantiate the spring AI text splitter to intelligently chunk large diffs without breaking words
    protected final TokenTextSplitter textSplitter = new TokenTextSplitter(512, 100, 10, 50, true);

    protected AbstractGitLabEventHandler(
            ChatModel chatModel,
            CodeChunkRepository chunkRepository,
            RepoIngestionWorkerService ingestionService,
            EmbeddingModel embeddingModel,
            String gitlabUrl,
            String gitlabToken,
            Resource codeReviewPromptResource
    ) {
        this.chatModel = chatModel;
        this.chunkRepository = chunkRepository;
        this.ingestionService = ingestionService;
        this.embeddingModel = embeddingModel;
        this.gitlabUrl = gitlabUrl;
        this.gitlabToken = gitlabToken;
        this.codeReviewPromptResource = codeReviewPromptResource;
    }

    //  template method that defines the skeleton of the AI code review pipeline
    protected final void executeAiReviewPipeline(
            Integer projectId,
            String targetIdentifier,
            String repoUrl,
            List<Diff> diffs
    ) {
        try {
            //  guard against empty or null changes to prevent null pointer exceptions
            if (diffs == null || diffs.isEmpty()) {
                log.info("No file changes detected for target {}", targetIdentifier);
                return;
            }

            //  block the thread until ingestion is 100% complete
            checkAndIngestRepo(repoUrl);

            StringBuilder diffBuilder = new StringBuilder();
            //  build a string representation of the git diff to pass to the AI. This includes the file path and the actual diff text.
            for (Diff diff : diffs) {
                diffBuilder.append("File: ").append(diff.getNewPath()).append("\n");
                diffBuilder.append(diff.getDiff()).append("\n\n");
            }

            String diffString = diffBuilder.toString();

            //  query the vector database for any existing files that are semantically related to the git diffs
            //  to provide additional context to the AI
            String relatedContext = getRelatedCodebaseContext(diffs, repoUrl);

            //  pass both the context and the diff to the AI
            String reviewComment = performAiCodeReview(diffString, relatedContext);
            String formattedComment = "**Guard AI Code Review:**\n\n" + reviewComment;

            GitLabApi gitLabApi = new GitLabApi(gitlabUrl, gitlabToken);

            //  call the abstract method to let the subclass handle the specific API interaction
            postReviewComment(gitLabApi, projectId, targetIdentifier, formattedComment);
            log.info("Successfully posted AI review for target {}", targetIdentifier);

        } catch (Exception e) {
            log.error("Failed to execute AI review pipeline for target {}", targetIdentifier, e);
        }
    }

    //  abstract method allowing subclasses to define exactly how the comment is posted
    protected abstract void postReviewComment(GitLabApi gitLabApi, Integer projectId, String targetIdentifier, String comment) throws Exception;

    //  searches the vector database for existing files that are semantically related to the git diffs
    private String getRelatedCodebaseContext(List<Diff> diffs, String repoUrl) {
        try {
            //  use a map to deduplicate chunks based on their file path and content,
            //  ensuring we don't overwhelm the AI with redundant information
            Map<String, CodeChunk> uniqueChunks = new HashMap<>();
            int chunksPerQuery = 5;

            //  iterate through each diff, generate an embedding for the changed code, and query pgvector for similar chunks
            //  in the same repository to provide contextual information about related files that might be impacted by the change
            for (Diff diff : diffs) {
                //  skip deleted files and binary files to avoid unnecessary processing and noise in the AI context
                String diffText = diff.getDiff();
                if (diffText == null || diffText.isBlank() || Boolean.TRUE.equals(diff.getDeletedFile())) continue;

                //  skip binary files which often appear in diffs as placeholders and don't contain meaningful text for embeddings
                String filePath = diff.getNewPath() != null ? diff.getNewPath() : diff.getOldPath();
                if (filePath != null && (filePath.endsWith(".png") || filePath.endsWith(".jpg") || filePath.endsWith(".jar") || filePath.endsWith(".class"))) {
                    continue;
                }

                //  clean the diff text by removing line prefixes and metadata to create a more coherent input for the
                //  embedding model, which improves the relevance of the retrieved context
                String cleanTextForEmbedding = diffText.replaceAll("(?m)^[+\\-]", "").replaceAll("(?m)^@@.*@@", "").trim();

                //  construct a document from the cleaned diff text and use the text splitter to break it into manageable chunks
                //  that fit within the embedding model's token limits, ensuring we capture as much relevant context as possible
                //  without losing important information due to truncation
                Document sourceDoc = new Document(cleanTextForEmbedding);
                List<Document> splitDocs = textSplitter.apply(List.of(sourceDoc));

                //  for each chunk, generate an embedding and query pgvector for similar chunks in the same repository to provide
                //  contextual information about related files that might be impacted by the change
                for (Document segment : splitDocs) {
                    //  generate an embedding for the chunk of changed code with a retry mechanism
                    //  to handle transient failures in the embedding service,
                    float[] diffVector = generateEmbeddingWithRetry("File: " + filePath + "\n" + segment.getText(), 3);
                    //  query pgvector for similar chunks in the same repository to provide contextual information about
                    //  related files that might be impacted by the change
                    List<CodeChunk> topChunks = chunkRepository.findSimilarChunksByRepo(diffVector, chunksPerQuery, repoUrl);
                    if (topChunks != null) {
                        //  add the retrieved chunks to the map for deduplication, using a combination of file path and content as the key
                        topChunks.forEach(chunk -> uniqueChunks.put(chunk.getFilePath() + chunk.getContent(), chunk));
                    }
                }
            }

            //  if no related chunks are found, return a message indicating that no existing contextual files were found in
            //  the database to provide feedback to the AI and avoid confusion
            if (uniqueChunks.isEmpty()) return "No existing contextual files found in the database.";

            //  return a formatted string of the unique related code chunks, including their file paths and content, to provide
            //  rich context to the AI for a more informed code review. This allows the AI to understand not just the changed code
            //  but also related files that might be impacted, leading to more accurate and insightful feedback.
            return uniqueChunks.values().stream()
                    .map(c -> "File: " + c.getFilePath() + "\nCode:\n" + c.getContent())
                    .collect(Collectors.joining("\n\n"));

        } catch (Exception e) {
            log.warn("Failed to fetch related context from pgvector. Proceeding with diff only.", e);
            return "Context retrieval failed.";
        }
    }

    //  checks if the repository exists in pgvector. if missing, triggers ingestion
    private void checkAndIngestRepo(String repoUrl) {
        //  initial quick check to see if the repo has already been ingested
        List<CodeChunk> initialCheck = chunkRepository.findByRepoUrl(repoUrl);

        //  if the repo is not found, trigger ingestion and block the thread until it's complete to ensure the AI has access to the codebase context
        if (initialCheck == null || initialCheck.isEmpty()) {
            log.info("Repo not found in pgvector. Triggering ingestion and waiting for completion...");
            //  trigger asynchronous ingestion
            ingestionService.ingestRepositoryAsync(
                    RepoIngestionRequestDto.builder()
                            .repoUrl(repoUrl)
                            .repoUsername("oauth2")
                            .repoAccessToken(gitlabToken)
                            .build(),
                    UUID.randomUUID().toString()
            );

            int attempts = 0;
            int maxAttempts = 120;

            //  poll every 5 seconds for up to 10 minutes to check if the ingestion is complete
            //  and the chunks are available in the database
            while (attempts < maxAttempts) {
                try {
                    //  wait for 5 seconds before checking the database again to give the ingestion worker time to process
                    Thread.sleep(5000);
                    attempts++;
                    List<CodeChunk> currentChunks = chunkRepository.findByRepoUrl(repoUrl);

                    //  if chunks are found, break the loop and proceed with the AI review.
                    //  This ensures we don't start vector search until the codebase context is available.
                    if (currentChunks != null && !currentChunks.isEmpty()) {
                        log.info("Ingestion verified! Found {} chunks in database. Proceeding to Vector Search.", currentChunks.size());
                        break;
                    }

                    //  log every 30 seconds to avoid spamming the logs while waiting
                    if (attempts % 6 == 0) {
                        log.info("Still waiting for repository ingestion to finish... ({} seconds elapsed)", attempts * 5);
                    }
                } catch (InterruptedException e) {
                    //  if the thread is interrupted while sleeping, exit the loop and log the interruption
                    Thread.currentThread().interrupt();
                    log.error("Thread was interrupted while waiting for ingestion", e);
                    break;
                }
            }
        }
    }

    //  performs the AI code review using prompt template
    private String performAiCodeReview(String gitDiff, String context) {
        PromptTemplate promptTemplate = new PromptTemplate(codeReviewPromptResource);
        Map<String, Object> model = Map.of(
                "related_context", context,
                "git_diff", gitDiff
        );
        String renderedPrompt = promptTemplate.render(model);
        return chatModel.call(renderedPrompt);
    }

    //  generates an embedding for the given content with an exponential backoff retry mechanism
    // Note: Transient failures in the embedding service can occur due to rate limits, network issues, or temporary service
    // disruptions. Implementing a retry mechanism with exponential backoff helps to mitigate these issues by
    // spacing out retry attempts and giving the service time to recover, increasing the likelihood of a successful response
    private float[] generateEmbeddingWithRetry(String content, int maxRetries) {
        int attempt = 0;
        Exception lastException = null;

        //  implement an exponential backoff strategy for retries, which is a common best practice for handling transient
        //  failures in external services like embedding models. This approach increases the wait time between each retry attempt
        //  reducing the load on the service and increasing the chances of a successful response in subsequent attempts.
        while (attempt < maxRetries) {
            try {
                //  attempt to generate the embedding for the given content. If successful, return the embedding vector.
                return embeddingModel.embed(content);
            } catch (Exception err) {
                lastException = err;
                attempt++;
                //  log a warning with the attempt number and the error message, and then wait for an exponentially
                //  increasing amount of time before retrying.
                if (attempt < maxRetries) {
                    int waitTime = 5000 * attempt;
                    log.warn("Webhook embedding generation failed, attempt {}/{}. Waiting {}ms before retry...", attempt, maxRetries, waitTime);
                    try {
                        //  wait for the calculated backoff time before retrying to give the embedding service
                        //  time to recover from transient issues
                        Thread.sleep(waitTime);
                    } catch (InterruptedException ie) {
                        //  if the thread is interrupted while sleeping, exit the loop and log the interruption
                        Thread.currentThread().interrupt();
                    }
                }
            }
        }
        throw new RuntimeException("Failed to generate embedding after " + maxRetries + " attempts", lastException);
    }
}
