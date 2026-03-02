package com.repo.guard.service.gitlab;

import com.fasterxml.jackson.databind.JsonNode;
import com.repo.guard.model.repo.CodeChunkRepository;
import com.repo.guard.service.repo.RepoIngestionWorkerService;
import lombok.extern.slf4j.Slf4j;
import org.gitlab4j.api.GitLabApi;
import org.gitlab4j.api.models.Diff;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;

import java.util.List;

@Slf4j
@Service
public class PushEventHandler extends AbstractGitLabEventHandler {
    public PushEventHandler(
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

    //  this handler supports "push" events from GitLab webhooks
    @Override
    public boolean supports(String eventType) {
        return "push".equals(eventType);
    }

    //  handles push events to trigger AI code review on feature branches (but ignores pushes to the default branch
    //  since those are reviewed via merge requests)
    @Override
    public void handleEvent(JsonNode payload) throws Exception {
        //  extract the commit SHA from the payload to identify the specific code changes being pushed
        String commitSha = payload.path("after").asText();

        //  ignore branch deletions where the 'after' hash is all zeros
        if (commitSha == null || commitSha.replace("0", "").isEmpty()) return;

        //  extract the project ID, repo URL, and ref from the payload to determine the branch being pushed to
        Integer projectId = payload.path("project_id").asInt();
        String repoUrl = payload.path("project").path("web_url").asText();
        String ref = payload.path("ref").asText();
        String defaultBranch = payload.path("project").path("default_branch").asText();

        //  determine if the push is to the default branch by checking if the ref ends with the default branch name
        boolean isDefaultBranch = ref != null && defaultBranch != null && ref.endsWith("/" + defaultBranch);

        //  if the push is to the default branch, skip the AI review since those changes should go through a merge request
        //  for proper context and review quality
        if (isDefaultBranch) {
            log.info("Ignored push to main branch. Vector DB updates are handled via Merge Request approval.");
            return;
        }

        log.info("Started AI Code Review for Feature Branch Push: {}", commitSha);

        //  initialize the GitLab API client to retrieve the diff of the pushed commit for AI review processing
        GitLabApi gitLabApi = new GitLabApi(gitlabUrl, gitlabToken);
        List<Diff> diffs = gitLabApi.getCommitsApi().getDiff(projectId, commitSha);

        //  trigger the inherited template method to handle the AI processing
        executeAiReviewPipeline(projectId, commitSha, repoUrl, diffs);
    }

    @Override
    protected void postReviewComment(GitLabApi gitLabApi, Integer projectId, String targetIdentifier, String comment) throws Exception {
        //  post comment directly on the commit itself
        gitLabApi.getCommitsApi().addComment(projectId, targetIdentifier, comment);
    }
}
