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

import java.util.*;

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
    @Async
    //  runs in the background to check out the diff, query context,
    //  and post the review without blocking gitlab's webhook timeout
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
     * processes a merge request. If opened/updated, it does an AI Review.
     * If MERGED, it triggers the Delta Sync to update the vector database!
     *
     * @param payload
     * @throws Exception
     */
    private void handleMergeRequest(JsonNode payload) throws Exception {
        String action = payload.path("object_attributes").path("action").asText();
        Integer projectId = payload.path("project").path("id").asInt();
        Long mrIid = payload.path("object_attributes").path("iid").asLong();
        String repoUrl = payload.path("project").path("web_url").asText();
        String targetBranch = payload.path("object_attributes").path("target_branch").asText();
        String defaultBranch = payload.path("project").path("default_branch").asText();

        // --- NEW CRITICAL FIX: Extract the exact immutable commit SHA of the merge ---
        String mergeCommitSha = payload.path("object_attributes").path("merge_commit_sha").asText();

        // Fallback to the branch name just in case the SHA is missing from the payload
        String fetchRef = (mergeCommitSha != null && !mergeCommitSha.isBlank() && !"null".equals(mergeCommitSha))
                ? mergeCommitSha
                : targetBranch;

        GitLabApi gitLabApi = new GitLabApi(gitlabUrl, gitlabToken);

        // =================================================================================
        // SCENARIO 1: MR IS APPROVED & MERGED (Trigger Delta Sync)
        // =================================================================================
        if ("merge".equals(action)) {
            // Ensure we only update the DB if it's merging into the main/master branch
            boolean isTargetingDefault = defaultBranch != null && targetBranch.equals(defaultBranch);

            if (isTargetingDefault) {
                log.info("Merge Request #{} successfully merged into main! Triggering Delta Sync...", mrIid);

                MergeRequest mrWithChanges = gitLabApi.getMergeRequestApi().getMergeRequestChanges(projectId, mrIid);

                Set<String> filesToUpdate = new HashSet<>();
                Set<String> filesToDelete = new HashSet<>();

                // Parse the approved diffs to figure out exactly what was added/removed
                if (mrWithChanges.getChanges() != null) {
                    for (Diff diff : mrWithChanges.getChanges()) {
                        if (Boolean.TRUE.equals(diff.getDeletedFile())) {
                            filesToDelete.add(diff.getOldPath());
                        } else if (Boolean.TRUE.equals(diff.getRenamedFile())) {
                            filesToDelete.add(diff.getOldPath()); // remove old vector
                            filesToUpdate.add(diff.getNewPath()); // embed new vector
                        } else {
                            filesToUpdate.add(diff.getNewPath());
                        }
                    }
                }

                // Fire the Delta Sync Worker!
                ingestionService.syncDeltaAsync(projectId, repoUrl, gitlabUrl, gitlabToken, fetchRef, filesToUpdate, filesToDelete);
            } else {
                // Handles sub-feature branch merges (e.g., merging feature-2 into feature-1)
                log.info("Merge Request #{} merged into a sub-branch ({}). Skipping Vector DB update to protect root context.", mrIid, targetBranch);
            }
            return; // Exit early, no AI review needed for a closed MR
        }

        // =================================================================================
        // SCENARIO 2: MR OPENED OR UPDATED (Trigger AI Review)
        // =================================================================================
        //  only review when mr is opened or updated
        if (!"open".equals(action) && !"update".equals(action)) return;

        log.info("Started AI Code Review for Merge Request #{}", mrIid);

        //  this will now block the thread until ingestion is 100% complete
        checkAndIngestRepo(repoUrl);

        MergeRequest mrWithChanges = gitLabApi.getMergeRequestApi().getMergeRequestChanges(projectId, mrIid);

        //  guard against empty or null changes to prevent null pointer exceptions
        if (mrWithChanges.getChanges() == null || mrWithChanges.getChanges().isEmpty()) {
            log.info("No file changes detected in Merge Request #{}", mrIid);
            return;
        }

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

        //  post comment to the merge request thread (fixed API signature)
        gitLabApi.getNotesApi().createMergeRequestNote(projectId, mrIid, formattedComment, null, false);
        log.info("Successfully posted AI review to GitLab MR #{}", mrIid);
    }

    /**
     * processes a direct commit (push).
     * Ignores the main branch completely. Only conducts AI Code Reviews for feature branches.
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

        String ref = payload.path("ref").asText();
        String defaultBranch = payload.path("project").path("default_branch").asText();

        // If this push is to main (e.g., from an MR merge), completely ignore it.
        // The handleMergeRequest method is taking care of the Delta Sync!
        boolean isDefaultBranch = ref != null && defaultBranch != null && ref.endsWith("/" + defaultBranch);

        if (isDefaultBranch) {
            log.info("Ignored push to main branch ({}). Vector DB updates are handled via Merge Request approval.", ref);
            return;
        }

        log.info("Started AI Code Review for Feature Branch Push: {}", commitSha);

        //  this will now block the thread until ingestion is 100% complete
        checkAndIngestRepo(repoUrl);

        GitLabApi gitLabApi = new GitLabApi(gitlabUrl, gitlabToken);

        //  fetch the specific diff for this single commit using the commits api
        List<Diff> diffs = gitLabApi.getCommitsApi().getDiff(projectId, commitSha);

        //  guard against empty commits
        if (diffs == null || diffs.isEmpty()) {
            log.info("No file changes detected in Commit {}", commitSha);
            return;
        }

        StringBuilder diffBuilder = new StringBuilder();
        //  iterate through the diffs to build a comprehensive diff string for the AI to analyze
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
                    float[] diffVector = generateEmbeddingWithRetry("File: " + filePath + "\n" + segment.getText(), 3);

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

        //  check if list is empty, because JPA never returns null for lists
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
            int maxAttempts = 120; //  10 minute timeout

            //  loop infinitely up to maxAttempts
            while (attempts < maxAttempts) {
                try {
                    Thread.sleep(5000);
                    attempts++;

                    //  re-query the database inside the loop
                    List<CodeChunk> currentChunks = chunkRepository.findByRepoUrl(repoUrl);

                    //  if chunks finally exist, break the loop and proceed to the AI review!
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
     * performs the AI code review using both the git diff and the surrounding codebase context,
     * heavily optimized for Gemini 2.5/3.0 reasoning capabilities and RAG architecture.
     *
     * @param gitDiff
     * @param context
     * @return
     */
    private String performAiCodeReview(String gitDiff, String context) {
        String prompt = """
            You are an elite Principal Software Engineer, Application Security Architect, and Context-Aware System Architect conducting an exhaustive Code Review.
            Your primary focus is WHOLE-PROJECT AWARENESS. You are provided with a Git Diff AND a Context block containing related files from the broader codebase.
            
            INSTRUCTIONS FOR DEEP ANALYSIS:
            1. WHOLE-CODEBASE AWARENESS (PRIMARY OBJECTIVE): Do not review the Git Diff in isolation. You MUST heavily cross-reference the Git Diff with the RELATED CODEBASE CONTEXT. Evaluate how the new changes interact with existing database schemas, service contracts, utility classes, and downstream consumers.
            2. Analyze Intent & Mechanics: Carefully analyze the Git Diff to fully understand the logic, intent, and execution of the changes.
            3. IGNORE NOISE: Do not comment on method reordering, whitespace changes, or formatting. Focus strictly on system-wide logic, security, and architecture.
            4. RISK SCORING: You must assign an overall severity score to this PR based on the highest-severity issue found.
               - CRITICAL: Immediate data breach, system crash, or severe financial loss risk. PR must be blocked.
               - HIGH: Significant vulnerability (e.g., IDOR, SSRF) or major architectural/contract-breaking flaw.
               - MEDIUM: Edge-case bugs, performance degradation, or missing error handling.
               - LOW: Minor tech debt, code smells, or non-optimal patterns.
               - SAFE: Mathematically, logically, and structurally sound.
            5. SPECIFIC VULNERABILITY HUNT (OWASP Top 10): You MUST explicitly scan the code for:
               - Injection Flaws (SQLi, Command Injection) and unsafe queries.
               - Concurrency issues & Race Conditions (e.g., TOCTOU, missing locking in transactional boundaries).
               - Hardcoded secrets, API keys, or sensitive credentials.
               - Broken Access Control (IDOR, missing authorization checks).
               - Server-Side Request Forgery (SSRF).
            6. Blast Radius & Dependent Files: Explicitly list the exact file paths, methods, or interfaces from the CONTEXT that will break or behave incorrectly due to this diff. If the <related_context> is empty, explicitly state: "No surrounding context provided; analyzing diff in isolation."
            7. If the code is perfectly SAFE, reply EXACTLY with: "### 🛡️ Overall Risk Assessment: **SAFE** \\n\\nLGTM! No critical issues found across the provided context. Excellent work."
            
            FORMAT YOUR RESPONSE USING THIS EXACT MARKDOWN STRUCTURE (If issues are found):
            
            ### Overall Risk Assessment: **[CRITICAL | HIGH | MEDIUM | LOW]**
            
            ### Summary of Changes
            [Provide a highly technical explanation of what the developer changed. Skip formatting/whitespace changes.]
            
            ### Critical Issues & Vulnerabilities
            [For each issue, start with a severity badge: **[CRITICAL]**, **[HIGH]**, or **[MEDIUM]**.]
            [Name the vulnerability using OWASP/CWE terminology if applicable. Explain the mechanics of the failure in depth.]
            
            ### Blast Radius (Cross-File Impact)
            [Relying heavily on the <related_context>, list the exact file paths, classes, and methods that are negatively impacted by this change. Explain how the contract or architecture was broken.]
            
            ### Performance & System-Wide Impact
            [Discuss any concurrency issues, N+1 query problems, memory leaks, or architectural anti-patterns introduced to the broader system.]
            
            ### Actionable Recommendations
            [Provide the exact code snippets or architectural refactoring needed to safely fix the critical issues. Ensure snippets are ready to be copy-pasted and fit the existing project context.]
            
            ### Additional Architectural Observations
            [Optional: Place non-critical **[LOW]** suggestions here, such as DRY principle violations across the codebase, naming conventions, or future-proofing advice.]
            
            --------------------------------------------------
            <related_context>
            %s
            </related_context>
            
            <git_diff>
            %s
            </git_diff>
            """.formatted(context, gitDiff);

        return chatModel.call(prompt);
    }

    /**
     * Generates an embedding for the given content with an exponential backoff retry mechanism.
     * Protects the webhook from transient Ollama C++ runner crashes (EOF errors).
     *
     * @param content The text to embed
     * @param maxRetries Maximum number of attempts
     * @return The vector array
     */
    private float[] generateEmbeddingWithRetry(String content, int maxRetries) {
        int attempt = 0;
        Exception lastException = null;

        //  loop to attempt embedding generation with exponential backoff
        while (attempt < maxRetries) {
            try {
                //  attempt to generate the embedding
                return embeddingModel.embed(content);
            } catch (Exception err) {
                lastException = err;
                attempt++;

                //  log the warning with attempt count and error message
                if (attempt < maxRetries) {
                    int waitTime = 5000 * attempt; // 5s, 10s, etc.
                    log.warn("Webhook embedding generation failed, attempt {}/{}. Waiting {}ms before retry...", attempt, maxRetries, waitTime);
                    try {
                        //  wait for the specified time before retrying
                        Thread.sleep(waitTime);
                    } catch (InterruptedException ie) {
                        //  if the thread is interrupted during sleep, log it and break the loop to prevent infinite retries
                        Thread.currentThread().interrupt();
                    }
                }
            }
        }
        //  if we exhaust all attempts, log the error and throw a runtime exception
        throw new RuntimeException("Failed to generate embedding after " + maxRetries + " attempts", lastException);
    }
}