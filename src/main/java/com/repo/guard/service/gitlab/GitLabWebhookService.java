package com.repo.guard.service.gitlab;

import com.fasterxml.jackson.databind.JsonNode;
import com.repo.guard.dto.RepoIngestionRequestDto;
import com.repo.guard.model.repo.CodeChunk;
import com.repo.guard.model.repo.CodeChunkRepository;
import com.repo.guard.service.repo.RepoIngestionWorkerService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.gitlab4j.api.GitLabApi;
import org.gitlab4j.api.models.Diff;
import org.gitlab4j.api.models.MergeRequest;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.ai.document.Document;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;

import java.util.List;
import java.util.UUID;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class GitLabWebhookService {

    private final ChatModel chatModel;
    private final CodeChunkRepository chunkRepository;
    private final RepoIngestionWorkerService ingestionService;
    private final EmbeddingModel embeddingModel;
    //  instantiate the spring ai text splitter to intelligently chunk large diffs without breaking words
    private final TokenTextSplitter textSplitter = new TokenTextSplitter(512, 100, 10, 50, true);

    @Value("${gitlab.api.url}")
    private String gitlabUrl;

    @Value("${gitlab.api.token}")
    private String gitlabToken;

    /**
     * routes the incoming gitlab event to the appropriate review logic based on its kind
     *
     * @param payload
     */
    @Async // runs in the background to check out the diff, query context,
    // and post the review without blocking gitlab's webhook timeout
    public void processGitlabEventAsync(JsonNode payload) {
        try {
            String objectKind = payload.path("object_kind").asText();

            //  route to merge request logic
            if ("merge_request".equals(objectKind)) {
                handleMergeRequest(payload);
            }
            //  route to direct commit/push logic
            else if ("push".equals(objectKind)) {
                handlePushEvent(payload);
            }

        } catch (Exception e) {
            log.error("Failed to process GitLab Webhook", e);
        }
    }

    /**
     * processes a merge request, fetches the diff, queries related context, and posts a review
     *
     * @param payload
     * @throws Exception
     */
    private void handleMergeRequest(JsonNode payload) throws Exception {
        String action = payload.path("object_attributes").path("action").asText();
        //  only review when mr is opened or updated
        if (!"open".equals(action) && !"update".equals(action)) return;

        Integer projectId = payload.path("project").path("id").asInt();
        Long mrIid = payload.path("object_attributes").path("iid").asLong();
        String repoUrl = payload.path("project").path("web_url").asText();

        log.info("Started AI Code Review for Merge Request #{}", mrIid);

        //  this will now block the thread until ingestion is 100% complete
        checkAndIngestRepo(repoUrl);

        GitLabApi gitLabApi = new GitLabApi(gitlabUrl, gitlabToken);
        MergeRequest mrWithChanges = gitLabApi.getMergeRequestApi().getMergeRequestChanges(projectId, mrIid);

        StringBuilder diffBuilder = new StringBuilder();
        for (Diff diff : mrWithChanges.getChanges()) {
            diffBuilder.append("File: ").append(diff.getNewPath()).append("\n");
            diffBuilder.append(diff.getDiff()).append("\n\n");
        }

        String diffString = diffBuilder.toString();

        //  we are guaranteed to have database chunks now
        String relatedContext = getRelatedCodebaseContext(mrWithChanges.getChanges(), repoUrl);

        //  pass both the context and the diff to the AI
        String reviewComment = performAiCodeReview(diffString, relatedContext);
        String formattedComment = "**Guard AI Code Review:**\n\n" + reviewComment;

        //  post comment to the merge request thread
        gitLabApi.getNotesApi().createMergeRequestNote(projectId, mrIid, formattedComment, null, false);
        log.info("Successfully posted AI review to GitLab MR #{}", mrIid);
    }

    /**
     * processes a direct commit (push), fetches the diff, queries related context, and posts a review
     *
     * @param payload
     * @throws Exception
     */
    private void handlePushEvent(JsonNode payload) throws Exception {
        //  the 'after' node contains the latest commit hash (sha) from the push
        String commitSha = payload.path("after").asText();

        //  ignore branch deletions where the 'after' hash is all zeros
        if (commitSha == null || commitSha.replace("0", "").isEmpty()) return;

        Integer projectId = payload.path("project_id").asInt();
        String repoUrl = payload.path("project").path("web_url").asText();

        log.info("Started AI Code Review for Push Commit: {}", commitSha);

        //  this will now block the thread until ingestion is 100% complete
        checkAndIngestRepo(repoUrl);

        GitLabApi gitLabApi = new GitLabApi(gitlabUrl, gitlabToken);

        //  fetch the specific diff for this single commit using the commits api
        List<Diff> diffs = gitLabApi.getCommitsApi().getDiff(projectId, commitSha);

        StringBuilder diffBuilder = new StringBuilder();
        for (Diff diff : diffs) {
            diffBuilder.append("File: ").append(diff.getNewPath()).append("\n");
            diffBuilder.append(diff.getDiff()).append("\n\n");
        }

        String diffString = diffBuilder.toString();

        //  we are guaranteed to have database chunks now
        String relatedContext = getRelatedCodebaseContext(diffs, repoUrl);

        //  pass both the context and the diff to the AI
        String reviewComment = performAiCodeReview(diffString, relatedContext);
        String formattedComment = "**Guard AI Code Review:**\n\n" + reviewComment;

        //  post comment directly on the commit itself
        gitLabApi.getCommitsApi().addComment(projectId, commitSha, formattedComment);
        log.info("Successfully posted AI review to GitLab Commit {}", commitSha);
    }

    /**
     * searches the vector database for existing files that are semantically related to the git diffs,
     * utilizing intelligent token splitting, binary filtering, and semantic cleaning
     *
     * @param diffs
     * @param repoUrl
     * @return
     */
    private String getRelatedCodebaseContext(List<Diff> diffs, String repoUrl) {
        try {
            //  use a map to deduplicate chunks by their unique file path and content
            Map<String, CodeChunk> uniqueChunks = new java.util.HashMap<>();

            //  reduced to 5. since we query per segment, 5 highly relevant chunks is safer for the llm context window
            int chunksPerQuery = 5;

            for (Diff diff : diffs) {
                String diffText = diff.getDiff();

                //  skip empty diffs or files that were completely deleted
                if (diffText == null || diffText.isBlank() || Boolean.TRUE.equals(diff.getDeletedFile())) continue;

                //  determine the correct file path
                String filePath = diff.getNewPath() != null ? diff.getNewPath() : diff.getOldPath();

                //  skip binary files and images to prevent embedding gibberish
                if (filePath != null && (filePath.endsWith(".png") || filePath.endsWith(".jpg") || filePath.endsWith(".jar") || filePath.endsWith(".class"))) {
                    continue;
                }

                //  strip git markers (+, -, @@) to create clean code for accurate semantic embedding search
                String cleanTextForEmbedding = diffText.replaceAll("(?m)^[+\\-]", "").replaceAll("(?m)^@@.*@@", "").trim();

                //  use spring ai's intelligent splitter instead of a naive string substring
                Document sourceDoc = new Document(cleanTextForEmbedding);
                List<Document> splitDocs = textSplitter.apply(List.of(sourceDoc));

                for (Document segment : splitDocs) {
                    //  embed this specific clean segment of the commit
                    float[] diffVector = embeddingModel.embed("File: " + filePath + "\n" + segment.getText());

                    //  pull related chunks and add them to our deduplication map
                    List<CodeChunk> topChunks = chunkRepository.findSimilarChunksByRepo(diffVector, chunksPerQuery, repoUrl);
                    if (topChunks != null) {
                        topChunks.forEach(chunk -> uniqueChunks.put(chunk.getFilePath() + chunk.getContent(), chunk));
                    }
                }
            }

            if (uniqueChunks.isEmpty()) {
                return "No existing contextual files found in the database.";
            }

            //  format the aggregated, deduplicated chunks into a readable context string for the ai
            return uniqueChunks.values().stream()
                    .map(c -> "File: " + c.getFilePath() + "\nCode:\n" + c.getContent())
                    .collect(java.util.stream.Collectors.joining("\n\n"));

        } catch (Exception e) {
            log.warn("Failed to fetch related context from pgvector. Proceeding with diff only.", e);
            return "Context retrieval failed.";
        }
    }

    /**
     * checks if the repository exists in pgvector. if missing, triggers ingestion and actively waits for it to complete.
     *
     * @param repoUrl
     */
    private void checkAndIngestRepo(String repoUrl) {
        List<CodeChunk> initialCheck = chunkRepository.findByRepoUrl(repoUrl);

        // check if list is empty, because JPA never returns null for lists
        if (initialCheck == null || initialCheck.isEmpty()) {
            log.info("Repo not found in pgvector. Triggering ingestion and waiting for completion...");
            ingestionService.ingestRepositoryAsync(
                    RepoIngestionRequestDto.builder()
                            .repoUrl(repoUrl)
                            .repoUsername("oauth2")
                            .repoAccessToken(gitlabToken)
                            .build(),
                    UUID.randomUUID().toString()
            );

            int attempts = 0;
            int maxAttempts = 120; // 10 minute timeout

            // Loop infinitely up to maxAttempts
            while (attempts < maxAttempts) {
                try {
                    Thread.sleep(5000);
                    attempts++;

                    // RE-QUERY the database inside the loop
                    List<CodeChunk> currentChunks = chunkRepository.findByRepoUrl(repoUrl);

                    // IF chunks finally exist, break the loop and proceed to the AI review!
                    if (currentChunks != null && !currentChunks.isEmpty()) {
                        log.info("Ingestion verified! Found {} chunks in database. Proceeding to Vector Search.", currentChunks.size());
                        break;
                    }

                    if (attempts % 6 == 0) {
                        log.info("Still waiting for repository ingestion to finish... ({} seconds elapsed)", attempts * 5);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    log.error("Thread was interrupted while waiting for ingestion", e);
                    break;
                }
            }
        }
    }

    /**
     * performs the AI code review using both the git diff and the surrounding codebase context
     *
     * @param gitDiff
     * @param context
     * @return
     */
    private String performAiCodeReview(String gitDiff, String context) {
        String prompt = """
            You are a Principal Software Engineer and Security Architect conducting an exhaustive, in-depth Code Review.
            You are provided with a Git Diff representing the latest changes, AND a Context block containing related files from the broader codebase.
            
            INSTRUCTIONS FOR DEEP ANALYSIS:
            1. Analyze Intent & Mechanics: Carefully analyze the Git Diff to fully understand the logic, intent, and execution of the changes.
            2. Repository-Wide Context: Cross-reference the Diff with the provided RELATED CODEBASE CONTEXT. Determine exactly how this change interacts with existing services, controllers, repositories, or utilities.
            3. Vulnerabilities & Bugs: Look for critical logic flaws, security vulnerabilities (e.g., SQL injection, broken access control), and severe performance bottlenecks (e.g., N+1 queries, memory leaks, infinite loops).
            4. Blast Radius & Dependent Files: If this change alters a method signature, changes database behavior, or modifies shared logic, you MUST explicitly list the exact file paths from the CONTEXT that will break or be negatively impacted.
            5. Be Informational & Thorough: Do not just point out the error. Explain the underlying mechanics of WHY it is an error, what the runtime consequence will be, and how it affects the system architecture.
            6. If the code is mathematically, logically, and structurally sound, and does not negatively impact the related context, reply EXACTLY with: "LGTM! No critical issues found."
            
            FORMAT YOUR RESPONSE USING THIS EXACT MARKDOWN STRUCTURE (If issues are found):
            
            ### Summary of Changes
            [Provide a detailed, technical explanation of what the developer changed.]
            
            ### Critical Issues & Vulnerabilities
            [List severe bugs or security flaws. Explain the mechanics of the failure in depth.]
            
            ### Blast Radius (Affected Files)
            [List the exact file paths of dependent classes/interfaces from the context that will fail, compile error, or behave incorrectly due to this change. Explain exactly how they are impacted.]
            
            ### Performance & Architectural Impact
            [Discuss any N+1 query problems, memory issues, or architectural anti-patterns introduced.]
            
            ### Actionable Recommendations
            [Provide the exact code snippets or architectural refactoring needed to safely fix the issues.]
            
            --------------------------------------------------
            RELATED CODEBASE CONTEXT:
            %s
            
            GIT DIFF TO REVIEW:
            %s
            """.formatted(context, gitDiff);

        return chatModel.call(prompt);
    }
}