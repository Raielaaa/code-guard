package com.repo.guard.service.repo;

import com.repo.guard.dto.RepoIngestionRequestDto;
import com.repo.guard.model.repo.CodeChunk;
import com.repo.guard.model.repo.CodeChunkRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.jgit.api.Git;
import org.gitlab4j.api.GitLabApi;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.FileSystemUtils;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
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
    private final EmbeddingModel embeddingModel;
    private final TransactionTemplate transactionTemplate;
    //  splitter that guarantees chunks are <= 512 tokens, with 300 token overlap, and a max of 10 splits per file
    private final TokenTextSplitter textSplitter = new TokenTextSplitter(512, 300, 10, 50, true);

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
            //  process the cloned files to extract code chunks and prepare them for embedding generation and db insertion
            List<CodeChunk> chunksToInsert = processFiles(tempDir, repo);
            //  generate embeddings for each code chunk sequentially, with progress logging
            generateEmbeddings(chunksToInsert);
            //   perform a batch insert of all the processed chunks with their embeddings into the db
            saveToDatabase(repo, chunksToInsert);

            //  log completion of the job with the total number of chunks inserted into the database
            log.info("Job {} COMPLETED. Inserted {} vectors.", jobId, chunksToInsert.size());
        } catch (Exception err) {
            //  log any exceptions that occur during the cloning, processing, embedding generation,
            //  or db insertion steps to help with debugging and monitoring
            log.error("Job Failed", err);
        } finally {
            //  ensure that the temporary directory used for cloning the repo is deleted after processing to free up disk space,
            //  even if errors occurred
            FileSystemUtils.deleteRecursively(tempDir);
        }
    }

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

        // 1. DELETE OLD VECTORS (for both modified and deleted files)
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

        // 2. FETCH & EMBED ONLY THE CHANGED FILES
        GitLabApi gitLabApi = new GitLabApi(gitlabUrl, gitlabToken);

        String safeRef = branch;
        try {
            // The getBranch API properly encodes slashes. We use it to grab the raw SHA hash.
            safeRef = gitLabApi.getRepositoryApi().getBranch(projectId, branch).getCommit().getId();
            log.info("Successfully resolved branch '{}' to commit SHA: {}", branch, safeRef);
        } catch (Exception e) {
            // If 'branch' is already a SHA (or if it fails), this catch block safely ignores it
            log.warn("Could not resolve branch to SHA. Proceeding with raw ref: {}", safeRef);
        }

        List<CodeChunk> newChunks = new ArrayList<>();

        for (String filePath : filesToUpdate) {
            // Skip binaries
            if (filePath.endsWith(".png") || filePath.endsWith(".jpg") || filePath.endsWith(".jar") || filePath.endsWith(".class")) continue;

            try {
                // Download the raw file directly into memory using GitLab's API
                // read the InputStream and convert it to a UTF-8 String
                String content = "";
                try (InputStream inputStream = gitLabApi.getRepositoryFileApi().getRawFile(projectId, filePath, safeRef)) {
                    content = new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
                }

                if (content == null || content.isBlank()) continue;

                Document sourceDoc = new Document(content);
                List<Document> splitDocs = textSplitter.apply(List.of(sourceDoc));

                for (int i = 0; i < splitDocs.size(); i++) {
                    String displayPath = filePath;
                    if (splitDocs.size() > 1) displayPath += " (Part " + (i + 1) + "/" + splitDocs.size() + ")";

                    CodeChunk chunk = CodeChunk.builder()
                            .repoUrl(repoUrl)
                            .filePath(displayPath)
                            .content(splitDocs.get(i).getText())
                            .build();

                    // Use our robust retry method!
                    chunk.setEmbedding(generateEmbeddingWithRetry(chunk.getContent(), i, 3));
                    newChunks.add(chunk);
                }
            } catch (Exception e) {
                log.error("Delta Sync: Failed to fetch or embed file: " + filePath, e);
            }
        }

        // 3. SAVE TO DB
        if (!newChunks.isEmpty()) {
            codeChunkRepository.saveAll(newChunks);
            log.info("Delta Sync complete! Inserted {} new vectors.", newChunks.size());
        }
    }

    /**
     * validates the repository by checking its existence and accessibility using the GitValidationService before attempting to clone it
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
     * clones the repository into a specified temporary directory using JGit,
     * ensuring that any existing directory is cleared first to avoid conflicts
     *
     * @param repo
     * @param tempDir
     * @throws Exception
     */
    private void cloneRepository(RepoIngestionRequestDto repo, File tempDir) throws Exception {
        //  clone the repo into the temp directory
        log.info("Cloning repo to: {}", tempDir.getAbsolutePath());

        //  if the temp directory already exists, delete it first to avoid conflicts
        if (tempDir.exists()) FileSystemUtils.deleteRecursively(tempDir);

        //  create the credentials provider using the token
        //  note: gitlab requires the username to be non-empty, but it ignores the actual
        //  username string as long as the password is a valid personal access token.
        UsernamePasswordCredentialsProvider credentialsProvider =
                new UsernamePasswordCredentialsProvider(
                        repo.getRepoUsername() != null ? repo.getRepoUsername() : "oauth2",
                        repo.getRepoAccessToken()
                );

        //  clone the repository using JGit with authentication
        Git.cloneRepository()
                .setURI(repo.getRepoUrl())
                .setDirectory(tempDir)
                .setCredentialsProvider(credentialsProvider) // <-- THIS IS THE CRITICAL FIX
                .call();
    }

    /**
     * processes the cloned repository files by walking through all files in the temp directory,
     * filtering for relevant file types
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
            //  filter to only include regular files with specific extensions (e.g., .java, .kt, .md, .gradle.kts)
            paths.filter(Files::isRegularFile)
                    .filter(path -> {
                        String p = path.toString();
                        return p.endsWith(".java")
                                || p.endsWith(".kt")
                                || p.endsWith(".md")
                                || p.endsWith(".gradle.kts");
                    })
                    //  for each relevant file, process it to extract code chunks and prepare them
                    //  for embedding generation and db insertion
                    .forEach(path -> processSingleFile(path, tempDir, repo, chunksToInsert));
        } catch (IOException err) {
            //  throw runtime exception to trigger the outer catch block
            log.error("Failed to walk through files in the cloned repository.", err);
            throw new RuntimeException(err);
        }
        return chunksToInsert;
    }

    /**
     * processes a single file by reading its content, splitting it into smaller chunks using the text splitter
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
                //  wrap raw content
                Document sourceDoc = new Document(content);
                //  split the document into smaller parts using the text splitter
                List<Document> splitDocs = textSplitter.apply(List.of(sourceDoc));

                //  process each split part and prepare it for db insertion
                for (int i = 0; i < splitDocs.size(); i++) {
                    Document splitPart = splitDocs.get(i);

                    //  create a display path for the chunk by removing the temp directory prefix
                    String displayPath = path.toString().replace(tempDir.getAbsolutePath(), "");
                    //  if the original file was split into multiple chunks, append a part indicator to the display path
                    if (splitDocs.size() > 1) displayPath += " (Part " + (i + 1) + "/" + splitDocs.size() + ")";

                    //  add the chunk to the list of chunks to insert, with repo URL, display path, and chunk content
                    chunksToInsert.add(CodeChunk.builder()
                            .repoUrl(repo.getRepoUrl())
                            .filePath(displayPath)
                            .content(splitPart.getText())
                            .build());
                }
            }
        } catch (IOException err) {
            //  show error but continue processing other files, as one failed file shouldn't stop the entire ingestion
            log.error("Failed to read file: " + path, err);
        }
    }

    /**
     * generates embeddings for each code chunk sequentially, utilizing a warmup
     * and optimistic execution backed by exponential backoff.
     *
     * * @param chunksToInsert
     */
    private void generateEmbeddings(List<CodeChunk> chunksToInsert) {
        log.info("Parsed {} chunks. Generating embeddings...", chunksToInsert.size());

        //  warm up the model with a test embedding to ensure it's fully loaded into unified memory
        try {
            log.info("Warming up embedding model...");
            //  this initial call may take extra time as it loads the model, but it helps ensure subsequent calls are faster and more consistent
            embeddingModel.embed("model warmup test");
            log.info("Model warmup complete.");
        } catch (Exception e) {
            log.warn("Model warmup failed, continuing anyway", e);
        }

        int batchSize = 10;

        //  process chunks optimistically. run as fast as possible and only delay if Ollama fails.
        for (int i = 0; i < chunksToInsert.size(); i++) {
            CodeChunk chunk = chunksToInsert.get(i);

            //  generate embedding vector with our safety net
            float[] vector = generateEmbeddingWithRetry(chunk.getContent(), i, 3);
            chunk.setEmbedding(vector);

            //  log progress every batchSize chunks to provide visibility into the embedding generation process
            if (i > 0 && i % batchSize == 0) {
                log.info("Processed {}/{} chunks...", i, chunksToInsert.size());
            }
        }
    }

    /**
     * generates an embedding for the given content with an exponential backoff retry mechanism
     * to handle transient failures from the embedding model
     *
     * @param content
     * @param chunkIndex
     * @param maxRetries
     * @return
     */
    private float[] generateEmbeddingWithRetry(String content, int chunkIndex, int maxRetries) {
        int attempt = 0;
        Exception lastException = null;

        //  loop until successful or max retries are reached
        while (attempt < maxRetries) {
            try {
                //  attempt to generate the embedding using the model
                return embeddingModel.embed(content);
            } catch (Exception err) {
                lastException = err;
                attempt++;

                //  if we haven't reached max retries, calculate wait time and pause before retrying
                if (attempt < maxRetries) {
                    int waitTime = 5000 * attempt;
                    log.warn("Embedding generation failed for chunk {}, attempt {}/{}. Waiting {}ms before retry...", chunkIndex, attempt, maxRetries, waitTime);
                    try {
                        //  pause the thread for the calculated wait time
                        Thread.sleep(waitTime);
                    } catch (InterruptedException ie) {
                        //  restore the interrupted status
                        Thread.currentThread().interrupt();
                    }
                }
            }
        }

        //  if all retries fail, throw an exception with the last encountered error
        throw new RuntimeException("Failed to generate embedding after " + maxRetries + " attempts", lastException);
    }

    /**
     * saves the processed code chunks with their embeddings to the database by performing a bulk insert within a transaction
     *
     * @param repo
     * @param chunksToInsert
     */
    private void saveToDatabase(RepoIngestionRequestDto repo, List<CodeChunk> chunksToInsert) {
        //  perform a batch insert into the database
        transactionTemplate.execute(status -> {
            //  before inserting new chunks, delete all existing chunks for this repo to avoid duplicates and ensure data consistency
            log.info("Clearing old vectors for repo: {}", repo.getRepoUrl());
            codeChunkRepository.deleteByRepoUrl(repo.getRepoUrl());
            //  save all the new chunks with their embeddings to the database in a single batch operation for efficiency
            codeChunkRepository.saveAll(chunksToInsert);
            return null;
        });
    }
}