package com.repo.guard.service.repo;

import com.repo.guard.dto.RepoIngestionRequestDto;

public interface RepoIngestionWorkerService {
    void ingestRepositoryAsync(RepoIngestionRequestDto repo, String jobId);
}
