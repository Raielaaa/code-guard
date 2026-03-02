package com.repo.guard.service.gitlab;

import com.fasterxml.jackson.databind.JsonNode;
import com.repo.guard.model.repo.CodeChunkRepository;
import com.repo.guard.service.repo.RepoIngestionWorkerService;
import lombok.extern.slf4j.Slf4j;
import org.gitlab4j.api.GitLabApi;
import org.gitlab4j.api.models.Diff;
import org.gitlab4j.api.models.MergeRequest;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;

import java.util.HashSet;
import java.util.Set;

@Slf4j
@Service
public class MergeRequestEventHandler extends AbstractGitLabEventHandler {
    public MergeRequestEventHandler(
            ChatModel chatModel,
            CodeChunkRepository chunkRepository,
            RepoIngestionWorkerService ingestionService,
            EmbeddingModel embeddingModel,
            @Value("${gitlab.api.url}") String gitlabUrl,
            @Value("${gitlab.api.token}") String gitlabToken,
            @Value("classpath:/static/code-review.st") Resource codeReviewPromptResource
    ) {
        super(chatModel, chunkRepository, ingestionService, embeddingModel, gitlabUrl, gitlabToken, codeReviewPromptResource);
    }

    //  this handler supports "merge_request" events from GitLab webhooks
    @Override
    public boolean supports(String eventType) {
        return "merge_request".equals(eventType);
    }

    //  handles both the creation/update of a merge request (to trigger AI review) and the merge action (to trigger delta sync)
    @Override
    public void handleEvent(JsonNode payload) throws Exception {
        String action = payload.path("object_attributes").path("action").asText();
        Integer projectId = payload.path("project").path("id").asInt();
        Long mrIid = payload.path("object_attributes").path("iid").asLong();
        String repoUrl = payload.path("project").path("web_url").asText();
        String targetBranch = payload.path("object_attributes").path("target_branch").asText();
        String defaultBranch = payload.path("project").path("default_branch").asText();

        GitLabApi gitLabApi = new GitLabApi(gitlabUrl, gitlabToken);

        //  if the merge request was merged, trigger a delta sync to update the vector DB with the new code state
        if ("merge".equals(action)) {
            //  only trigger the delta sync if the merge was into the default branch to protect the root context of the vector DB
            boolean isTargetingDefault = defaultBranch != null && targetBranch.equals(defaultBranch);

            //  if merged into a sub-branch, skip the delta sync to avoid polluting the vector DB with
            //  intermediate code states that could degrade AI review quality
            if (isTargetingDefault) {
                log.info("Merge Request #{} successfully merged into {}! Triggering Delta Sync...", mrIid, defaultBranch);
                //  retrieve the list of changed files in the merge request to optimize the delta sync process
                MergeRequest mrWithChanges = gitLabApi.getMergeRequestApi().getMergeRequestChanges(projectId, mrIid);

                Set<String> filesToUpdate = new HashSet<>();
                Set<String> filesToDelete = new HashSet<>();

                if (mrWithChanges.getChanges() != null) {
                    //  analyze the changes to determine which files were added/modified (to update) vs
                    //  deleted/renamed (to delete old path and add new path)
                    for (Diff diff : mrWithChanges.getChanges()) {
                        if (Boolean.TRUE.equals(diff.getDeletedFile())) {
                            filesToDelete.add(diff.getOldPath());
                        } else if (Boolean.TRUE.equals(diff.getRenamedFile())) {
                            filesToDelete.add(diff.getOldPath());
                            filesToUpdate.add(diff.getNewPath());
                        } else {
                            filesToUpdate.add(diff.getNewPath());
                        }
                    }
                }

                //  if the target project ID is different from the source project ID (in case of cross-project merge requests),
                //  use the target project ID for the delta sync
                Long targetProjectIdLong = mrWithChanges.getTargetProjectId();
                Integer targetProjectId = targetProjectIdLong != null ? targetProjectIdLong.intValue() : projectId;

                //  trigger the asynchronous delta sync process to update the vector DB with the new code state after the merge
                ingestionService.syncDeltaAsync(targetProjectId, repoUrl, gitlabUrl, gitlabToken, defaultBranch, filesToUpdate, filesToDelete);
            } else {
                log.info("Merge Request #{} merged into a sub-branch ({}). Skipping Vector DB update to protect root context.", mrIid, targetBranch);
            }
            return;
        }

        //  if the merge request was just created or updated, trigger the AI code review process to analyze the proposed
        //  changes and provide feedback in the merge request thread
        if (!"open".equals(action) && !"update".equals(action)) return;

        log.info("Started AI Code Review for Merge Request #{}", mrIid);
        //  retrieve the list of changed files in the merge request to provide context for the AI review
        MergeRequest mrWithChanges = gitLabApi.getMergeRequestApi().getMergeRequestChanges(projectId, mrIid);

        //  trigger the inherited template method to handle the AI processing
        executeAiReviewPipeline(projectId, String.valueOf(mrIid), repoUrl, mrWithChanges.getChanges());
    }

    //  this method is called by the inherited template method after the AI review comment has been generated,
    @Override
    protected void postReviewComment(GitLabApi gitLabApi, Integer projectId, String targetIdentifier, String comment) throws Exception {
        gitLabApi.getNotesApi().createMergeRequestNote(projectId, Long.parseLong(targetIdentifier), comment, null, false);
    }
}
