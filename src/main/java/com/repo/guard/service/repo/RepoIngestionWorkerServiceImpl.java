package com.repo.guard.service.repo;

import com.repo.guard.dto.RepoIngestionRequestDto;
import com.repo.guard.model.repo.CodeChunk;
import com.repo.guard.model.repo.CodeChunkRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.jgit.api.Git;
import org.springframework.ai.document.Document;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.FileSystemUtils;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;

@Slf4j
@Service
@RequiredArgsConstructor
public class RepoIngestionWorkerServiceImpl implements RepoIngestionWorkerService {
    private final GitValidationService gitValidationService;
    private final CodeChunkRepository codeChunkRepository;
    private final TransactionTemplate transactionTemplate;
    //  inject our new dedicated embedding facade instead of managing tokens here
    private final VectorEmbeddingService vectorEmbeddingService;

    /**
     * ingests a repository asynchronously
     *
     * @param repo
     * @param jobId
     */
    @Async
    @Override
    public void ingestRepositoryAsync(RepoIngestionRequestDto repo, String jobId) {
        log.info("Starting Async Job: {}", jobId);
        //  validate the repository before proceeding with cloning and ingestion
        if (!validateRepository(repo, jobId)) return;

        //  create a unique temp directory for this job to clone the repo into
        File tempDir = new File(System.getProperty("java.io.tmpdir"), "guard-app/" + jobId);

        try {
            //  clone the repository to the temp directory
            cloneRepository(repo, tempDir);
            //  process the cloned files to extract code chunks
            List<CodeChunk> chunksToInsert = processFiles(tempDir, repo);
            //  delegate the heavy lifting of mathematical embeddings to the dedicated service
            vectorEmbeddingService.generateEmbeddingsForChunks(chunksToInsert);
            //  perform a batch insert of all the processed chunks with their embeddings into the db
            saveToDatabase(repo, chunksToInsert);

            //  log completion of the job with the total number of chunks inserted into the database
            log.info("Job {} COMPLETED. Inserted {} vectors.", jobId, chunksToInsert.size());
        } catch (Exception err) {
            //  log any exceptions that occur during the cloning or db insertion steps
            log.error("Job Failed", err);
        } finally {
            //  ensure that the temporary directory used for cloning the repo is deleted after processing
            FileSystemUtils.deleteRecursively(tempDir);
        }
    }

    /**
     * performs a delta sync by cloning only the target branch, reading the changed files,
     * generating embeddings, and updating the corresponding vectors in the database
     *
     * @param projectId
     * @param repoUrl
     * @param gitlabUrl
     * @param gitlabToken
     * @param branch
     * @param filesToUpdate
     * @param filesToDelete
     */
    @Async
    public void syncDeltaAsync(
            Integer projectId,
            String repoUrl,
            String gitlabUrl,
            String gitlabToken,
            String branch,
            Set<String> filesToUpdate,
            Set<String> filesToDelete
    ) {
        log.info("Starting Delta Sync for {} update(s) and {} deletion(s)...", filesToUpdate.size(), filesToDelete.size());

        //  delete old vectors only for the specific modified and deleted files
        transactionTemplate.execute(status -> {
            for (String file : filesToDelete) {
                codeChunkRepository.deleteByRepoUrlAndFilePathStartingWith(repoUrl, file);
            }
            for (String file : filesToUpdate) {
                codeChunkRepository.deleteByRepoUrlAndFilePathStartingWith(repoUrl, file);
            }
            return null;
        });

        if (filesToUpdate.isEmpty()) return;

        //  shallow clone the target branch via JGit and read only the changed files
        File tempDir = new File(System.getProperty("java.io.tmpdir"), "guard-delta/" + System.currentTimeMillis());

        try {
            //  create the credentials provider using the token for JGit authentication
            UsernamePasswordCredentialsProvider credentialsProvider =
                    new UsernamePasswordCredentialsProvider("oauth2", gitlabToken);

            //  if the temp directory already exists, delete it first to avoid conflicts
            if (tempDir.exists()) FileSystemUtils.deleteRecursively(tempDir);

            log.info("Cloning branch '{}' for Delta Sync...", branch);

            //  shallow clone (depth=1) of only the target branch to minimize data transfer
            Git.cloneRepository()
                    .setURI(repoUrl)
                    .setDirectory(tempDir)
                    .setBranchesToClone(List.of("refs/heads/" + branch))
                    .setBranch("refs/heads/" + branch)
                    .setDepth(1)
                    .setCredentialsProvider(credentialsProvider)
                    .call()
                    .close();

            List<CodeChunk> newChunks = new ArrayList<>();

            //  read, split, and embed only the files that were changed in the merge request
            for (String filePath : filesToUpdate) {
                if (filePath.endsWith(".png") || filePath.endsWith(".jpg") || filePath.endsWith(".jar") || filePath.endsWith(".class")) continue;

                try {
                    //  resolve the file path within the cloned temp directory
                    Path localFile = tempDir.toPath().resolve(filePath);

                    //  if the file doesn't exist in the clone skip it but log a warning
                    if (!Files.exists(localFile)) {
                        log.warn("Delta Sync: File not found in clone: {}", filePath);
                        continue;
                    }

                    //  read the file content as a string
                    String content = Files.readString(localFile);
                    if (content == null || content.isBlank()) continue;

                    //  delegate the chunking logic to the external embedding service
                    List<Document> splitDocs = vectorEmbeddingService.splitText(content);

                    //  process each split part and prepare it for db insertion
                    for (int i = 0; i < splitDocs.size(); i++) {
                        String displayPath = filePath;
                        //  append a part indicator to the display path to differentiate them in the database
                        if (splitDocs.size() > 1) displayPath += " (Part " + (i + 1) + "/" + splitDocs.size() + ")";

                        CodeChunk chunk = CodeChunk.builder()
                                .repoUrl(repoUrl)
                                .filePath(displayPath)
                                .content(splitDocs.get(i).getText())
                                .build();

                        //  delegate embedding generation to the facade
                        chunk.setEmbedding(vectorEmbeddingService.generateEmbeddingWithRetry(chunk.getContent(), i, 3));
                        newChunks.add(chunk);
                    }
                } catch (Exception e) {
                    log.error("Delta Sync: Failed to process file: " + filePath, e);
                }
            }

            //  save the new vectors for only the changed files to the database
            if (!newChunks.isEmpty()) {
                codeChunkRepository.saveAll(newChunks);
                log.info("Delta Sync complete! Inserted {} new vectors.", newChunks.size());
            }
        } catch (Exception e) {
            log.error("Delta Sync: Failed to clone repository for delta update", e);
        } finally {
            //  always clean up the temp directory to free disk space
            FileSystemUtils.deleteRecursively(tempDir);
        }
    }

    /**
     * validates the repository by checking its existence and accessibility
     *
     * @param repo
     * @param jobId
     * @return
     */
    private boolean validateRepository(RepoIngestionRequestDto repo, String jobId) {
        //  validate repo existence and accessibility before cloning
        boolean exists = gitValidationService.isRemoteRepoAccessible(
                repo.getRepoUrl(),
                repo.getRepoUsername(),
                repo.getRepoAccessToken()
        );

        //  if repo doesn't exist or is inaccessible, log error and exit
        if (!exists) {
            log.error("Job {} FAILED: Repository not found or inaccessible.", jobId);
            return false;
        }

        //  if repo is valid, proceed with cloning and ingestion
        log.info("Repository found! Proceeding with clone and vector ingestion...");
        return true;
    }

    /**
     * clones the repository into a specified temporary directory using JGit
     *
     * @param repo
     * @param tempDir
     * @throws Exception
     */
    private void cloneRepository(RepoIngestionRequestDto repo, File tempDir) throws Exception {
        log.info("Cloning repo to: {}", tempDir.getAbsolutePath());

        //  if the temp directory already exists, delete it first to avoid conflicts
        if (tempDir.exists()) FileSystemUtils.deleteRecursively(tempDir);

        //  create the credentials provider using the token
        UsernamePasswordCredentialsProvider credentialsProvider =
                new UsernamePasswordCredentialsProvider(
                        repo.getRepoUsername() != null ? repo.getRepoUsername() : "oauth2",
                        repo.getRepoAccessToken()
                );

        //  clone the repository using JGit with authentication
        Git.cloneRepository()
                .setURI(repo.getRepoUrl())
                .setDirectory(tempDir)
                .setCredentialsProvider(credentialsProvider)
                .call();
    }

    /**
     * processes the cloned repository files by walking through all files in the temp directory
     *
     * @param tempDir
     * @param repo
     * @return
     */
    private List<CodeChunk> processFiles(File tempDir, RepoIngestionRequestDto repo) {
        //  holds all the code chunks that will be saved to the db after processing
        List<CodeChunk> chunksToInsert = new ArrayList<>();

        //  walk through all files in the cloned repo including subdirectories
        try (Stream<Path> paths = Files.walk(tempDir.toPath())) {
            //  filter to only include regular files with specific extensions
            paths.filter(Files::isRegularFile)
                    .filter(path -> {
                        String p = path.toString();
                        return p.endsWith(".java")
                                || p.endsWith(".kt")
                                || p.endsWith(".md")
                                || p.endsWith(".gradle.kts");
                    })
                    //  for each relevant file, process it to extract code chunks
                    .forEach(path -> processSingleFile(path, tempDir, repo, chunksToInsert));
        } catch (IOException err) {
            log.error("Failed to walk through files in the cloned repository.", err);
            throw new RuntimeException(err);
        }
        return chunksToInsert;
    }

    /**
     * processes a single file by reading its content and delegating splitting to the embedding service
     *
     * @param path
     * @param tempDir
     * @param repo
     * @param chunksToInsert
     */
    private void processSingleFile(Path path, File tempDir, RepoIngestionRequestDto repo, List<CodeChunk> chunksToInsert) {
        try {
            //  read the file content as a string
            String content = Files.readString(path);
            if (!content.isBlank()) {

                //  delegate the chunking logic to the external embedding service
                List<Document> splitDocs = vectorEmbeddingService.splitText(content);

                //  process each split part and prepare it for db insertion
                for (int i = 0; i < splitDocs.size(); i++) {
                    Document splitPart = splitDocs.get(i);

                    //  create a display path for the chunk by removing the temp directory prefix
                    String displayPath = tempDir.toPath().relativize(path).toString().replace("\\", "/");
                    //  if the original file was split into multiple chunks, append a part indicator to the display path
                    if (splitDocs.size() > 1) displayPath += " (Part " + (i + 1) + "/" + splitDocs.size() + ")";

                    //  add the chunk to the list of chunks to insert
                    chunksToInsert.add(CodeChunk.builder()
                            .repoUrl(repo.getRepoUrl())
                            .filePath(displayPath)
                            .content(splitPart.getText())
                            .build());
                }
            }
        } catch (IOException err) {
            //  show error but continue processing other files
            log.error("Failed to read file: " + path, err);
        }
    }

    /**
     * saves the processed code chunks with their embeddings to the database by performing a bulk insert
     *
     * @param repo
     * @param chunksToInsert
     */
    private void saveToDatabase(RepoIngestionRequestDto repo, List<CodeChunk> chunksToInsert) {
        //  perform a batch insert into the database
        transactionTemplate.execute(status -> {
            //  before inserting new chunks, delete all existing chunks for this repo to avoid duplicates
            log.info("Clearing old vectors for repo: {}", repo.getRepoUrl());
            codeChunkRepository.deleteByRepoUrl(repo.getRepoUrl());
            //  save all the new chunks with their embeddings to the database
            codeChunkRepository.saveAll(chunksToInsert);
            return null;
        });
    }
}
