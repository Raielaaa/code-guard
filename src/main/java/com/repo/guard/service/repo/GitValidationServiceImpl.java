package com.repo.guard.service.repo;

import io.netty.util.internal.StringUtil;
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
     * checks if a remote repository exists and is accessible
     *
     * @param repoUrl
     * @param username
     * @param token
     * @return boolean
     */
    @Override
    public boolean isRemoteRepoAccessible(String repoUrl, String username, String token) {
        try {
            //  lsRemoteRepository checks the remote heads (branches) without cloning the full data
            LsRemoteCommand command = Git.lsRemoteRepository()
                    .setRemote(repoUrl)
                    .setHeads(true)
                    .setTags(false);

            //  if using a private repo, inject credentials (JWT/OAuth token)
            if (!StringUtil.isNullOrEmpty(token))
                command.setCredentialsProvider(new UsernamePasswordCredentialsProvider(username, token));

            //  if call() succeeds, the repo exists, else, it doesn't or is inaccessible
            command.call();

            return true;
        } catch (GitAPIException err) {
            //  repo not found, 404, or Auth failed
            log.warn("Repo validation failed for {}: {}", repoUrl, err.getMessage());

            return false;
        }
    }
}
