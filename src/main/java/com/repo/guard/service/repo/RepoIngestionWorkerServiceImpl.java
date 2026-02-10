package com.repo.guard.service.repo;

import com.repo.guard.dto.RepoIngestionRequestDto;
import com.repo.guard.model.repo.CodeChunk;
import com.repo.guard.model.repo.CodeChunkRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.jgit.api.Git;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
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
import java.util.stream.Stream;

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

        //  clone the repository using JGit
        Git.cloneRepository()
                .setURI(repo.getRepoUrl())
                .setDirectory(tempDir)
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
     * generates embeddings for each code chunk sequentially by calling the embedding model
     * and sets the resulting vector on each chunk object
     *
     * @param chunksToInsert
     */
    private void generateEmbeddings(List<CodeChunk> chunksToInsert) {
        log.info("Parsed {} chunks. Generating embeddings...", chunksToInsert.size());
        //  process chunks sequentially to avoid overwhelming the embedding model
        int batchSize = 10;
        //  iterate through each chunk, generate its embedding, and set it on the chunk object
        for (int i = 0; i < chunksToInsert.size(); i++) {
            CodeChunk chunk = chunksToInsert.get(i);

            //  generate embedding vector for the chunk content using the embedding model
            float[] vector = embeddingModel.embed(chunk.getContent());
            //  set the generated embedding vector on the chunk object for later db insertion
            chunk.setEmbedding(vector);
            //  log progress every batchSize chunks to keep track of how many chunks have been processed
            if (i % batchSize == 0) log.info("Processed {}/{} chunks...", i, chunksToInsert.size());
        }
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