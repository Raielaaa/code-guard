package com.repo.guard.service.repo;

import com.repo.guard.dto.RepoIngestionRequestDto;

import java.util.Set;

public interface RepoIngestionWorkerService {
    void ingestRepositoryAsync(RepoIngestionRequestDto repo, String jobId);
    void syncDeltaAsync(
            Integer projectId,
            String repoUrl,
            String gitlabUrl,
            String gitlabToken,
            String branch,
            Set<String> filesToUpdate,
            Set<String> filesToDelete
    );
}
