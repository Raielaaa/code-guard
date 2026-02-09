package com.repo.guard.service.repo;

public interface GitValidationService {
    boolean isRemoteRepoAccessible(String repoUrl, String username, String token);
}
