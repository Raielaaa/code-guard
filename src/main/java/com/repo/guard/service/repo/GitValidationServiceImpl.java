package com.repo.guard.service.repo;

import lombok.extern.slf4j.Slf4j;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.LsRemoteCommand;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;
import org.springframework.stereotype.Service;

@Slf4j
@Service
public class GitValidationServiceImpl implements GitValidationService {
    /**
     * Checks if a remote repository exists and is accessible.
     */
    @Override
    public boolean isRemoteRepoAccessible(String repoUrl, String username, String token) {
        try {
            // lsRemoteRepository checks the remote heads (branches) without cloning the full data
            LsRemoteCommand command = Git.lsRemoteRepository()
                    .setRemote(repoUrl)
                    .setHeads(true)
                    .setTags(false);

            // If using a private repo, inject credentials (JWT/OAuth token)
            if (token != null && !token.isBlank()) {
                command.setCredentialsProvider(new UsernamePasswordCredentialsProvider(username, token));
            }

            // If call() succeeds, the repo exists. If it throws, it likely doesn't.
            command.call();

            return true;
        } catch (GitAPIException err) {
            // Log error: Repo not found, 404, or Auth failed
            log.warn("Repo validation failed for {}: {}", repoUrl, err.getMessage());

            return false;
        }
    }
}
