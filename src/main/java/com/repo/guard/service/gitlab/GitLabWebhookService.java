package com.repo.guard.service.gitlab;

import com.fasterxml.jackson.databind.JsonNode;
import com.repo.guard.dto.RepoIngestionRequestDto;
import com.repo.guard.model.repo.CodeChunkRepository;
import com.repo.guard.service.repo.RepoIngestionWorkerService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.gitlab4j.api.GitLabApi;
import org.gitlab4j.api.models.Diff;
import org.gitlab4j.api.models.MergeRequest;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class GitLabWebhookService {

    private final ChatModel chatModel;
    private final CodeChunkRepository chunkRepository;
    private final RepoIngestionWorkerService ingestionService;

    @Value("${gitlab.api.url}")
    private String gitlabUrl;

    @Value("${gitlab.api.token}")
    private String gitlabToken;

    /**
     * processes the incoming gitlab merge request event asynchronously
     *
     * @param payload
     */
    @Async
    public void processMergeRequestEventAsync(JsonNode payload) {
        try {
            String objectKind = payload.path("object_kind").asText();
            String action = payload.path("object_attributes").path("action").asText();

            //  only trigger the ai if a new mr is opened, or new code is pushed (updated)
            if (!"merge_request".equals(objectKind) || (!"open".equals(action) && !"update".equals(action))) {
                return;
            }

            Integer projectId = payload.path("project").path("id").asInt();
            Long mrIid = payload.path("object_attributes").path("iid").asLong();
            String repoUrl = payload.path("project").path("web_url").asText();

            log.info("Started AI Code Review for Merge Request #{}", mrIid);

            //  check if repo exists in pgvector. if not, ingest it
            if (chunkRepository.findByRepoUrl(repoUrl) == null) {
                log.info("Repo not found in pgvector. Triggering ingestion...");
                ingestionService.ingestRepositoryAsync(
                        RepoIngestionRequestDto.builder().repoUrl(repoUrl).build(),
                        UUID.randomUUID().toString()
                );
            }

            GitLabApi gitLabApi = new GitLabApi(gitlabUrl, gitlabToken);

            //  fetch the specific code changes (git diff) for this merge request
            MergeRequest mrWithChanges = gitLabApi.getMergeRequestApi().getMergeRequestChanges(projectId, mrIid);
            StringBuilder diffBuilder = new StringBuilder();
            for (Diff diff : mrWithChanges.getChanges()) {
                diffBuilder.append("File: ").append(diff.getNewPath()).append("\n");
                diffBuilder.append(diff.getDiff()).append("\n\n");
            }

            //  ask qwen to review the code changes
            String reviewComment = performAiCodeReview(diffBuilder.toString());

            //  post the ai's review as a comment on the gitlab merge request
            String formattedComment = "**Guard AI Code Review:**\n\n" + reviewComment;
            gitLabApi.getNotesApi().createMergeRequestNote(projectId, mrIid, formattedComment);

            log.info("Successfully posted AI review to GitLab MR #{}", mrIid);

        } catch (Exception e) {
            log.error("Failed to process GitLab Webhook", e);
        }
    }

    /**
     * performs the ai code review directly on the git diff
     *
     * @param gitDiff
     * @return
     */
    private String performAiCodeReview(String gitDiff) {
        String prompt = """
            You are an Expert Senior Software Engineer conducting a Code Review.
            Analyze the following Git Diff.
            
            INSTRUCTIONS:
            1. Identify strictly critical bugs, security vulnerabilities, or severe anti-patterns.
            2. If you find issues, list them clearly with the file name.
            3. If the code looks safe and correct, reply EXACTLY with: "LGTM! No critical issues found."
            4. Do not nitpick formatting or styling. Keep it concise.
            
            GIT DIFF:
            %s
            """.formatted(gitDiff);

        return chatModel.call(prompt);
    }
}