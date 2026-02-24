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
        checkAndIngestRepo(repoUrl);

        GitLabApi gitLabApi = new GitLabApi(gitlabUrl, gitlabToken);
        MergeRequest mrWithChanges = gitLabApi.getMergeRequestApi().getMergeRequestChanges(projectId, mrIid);

        StringBuilder diffBuilder = new StringBuilder();
        for (Diff diff : mrWithChanges.getChanges()) {
            diffBuilder.append("File: ").append(diff.getNewPath()).append("\n");
            diffBuilder.append(diff.getDiff()).append("\n\n");
        }

        String diffString = diffBuilder.toString();

        //  pass the raw List<Diff> to the context search instead of the String
        String relatedContext = getRelatedCodebaseContext(mrWithChanges.getChanges(), repoUrl);

        //  pass both the context and the diff to the AI
        String reviewComment = performAiCodeReview(diffString, relatedContext);
        String formattedComment = "**Guard AI Code Review:**\n\n" + reviewComment;

        //  post comment to the merge request thread
        gitLabApi.getNotesApi().createMergeRequestNote(projectId, mrIid, formattedComment);
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

        //  pass the raw List<Diff> to the context search instead of the String
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
     * checks if the repository exists in pgvector, triggers ingestion if missing
     *
     * @param repoUrl
     */
    private void checkAndIngestRepo(String repoUrl) {
        if (chunkRepository.findByRepoUrl(repoUrl) == null) {
            log.info("Repo not found in pgvector. Triggering ingestion...");
            ingestionService.ingestRepositoryAsync(
                    RepoIngestionRequestDto.builder().repoUrl(repoUrl).build(),
                    UUID.randomUUID().toString()
            );
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
            You are an Expert Senior Software Engineer conducting a thorough Code Review.
            You are provided with a Git Diff representing the latest changes, AND a Context block containing related files from the broader codebase.
            
            INSTRUCTIONS:
            1. Analyze the Git Diff to understand the changes made.
            2. Use the provided codebase CONTEXT to evaluate the impact of these changes on the rest of the application.
            3. Identify critical bugs, breaking changes, security vulnerabilities, or architectural issues caused by this diff.
            4. If a change in the diff breaks a method or logic in the related context, explain WHY.
            5. If the code is safe and does not negatively impact the related context, reply EXACTLY with: "LGTM! No critical issues found."
            6. Do not nitpick formatting. Keep it concise.
            
            RELATED CODEBASE CONTEXT:
            %s
            
            GIT DIFF TO REVIEW:
            %s
            """.formatted(context, gitDiff);

        return chatModel.call(prompt);
    }
}