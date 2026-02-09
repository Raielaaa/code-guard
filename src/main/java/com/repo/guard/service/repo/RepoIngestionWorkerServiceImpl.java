package com.repo.guard.service.repo;

import com.repo.guard.dto.RepoIngestionRequestDto;
import com.repo.guard.model.repo.CodeChunk;
import com.repo.guard.model.repo.CodeChunkRepository;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.jgit.api.Git;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.util.FileSystemUtils;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;
import java.util.stream.Stream;

@Slf4j
@Service
@AllArgsConstructor
public class RepoIngestionWorkerServiceImpl implements RepoIngestionWorkerService {
    private final GitValidationService gitValidationService;
    private final EmbeddingModel embeddingModel;
    private final CodeChunkRepository codeChunkRepository;

    @Async
    @Override
    public void ingestRepositoryAsync(RepoIngestionRequestDto repo, String jobId) {
        log.info("Starting Async Job: {}", jobId);

        boolean exists = gitValidationService.isRemoteRepoAccessible(
                repo.getRepoUrl(),
                repo.getRepoUsername(),
                repo.getRepoAccessToken()
        );

        if (!exists) {
            System.out.println("Job " + jobId + " FAILED: Repository not found or inaccessible.");
            return;
        }

        log.info("Repository found! Proceeding with clone and vector ingestion...");

        File tempDir = new File(System.getProperty("java.io.tmpdir"), "guard-app/" + jobId);

        try {
            log.info("Cloning repo to: {}", tempDir.getAbsolutePath());
            if (tempDir.exists()) {
                FileSystemUtils.deleteRecursively(tempDir);
            }

            Git.cloneRepository()
                    .setURI(repo.getRepoUrl())
                    .setDirectory(tempDir)
                    .call();

            List<CodeChunk> chunksToInsert = new ArrayList<>();
            try(Stream<Path> paths = Files.walk(tempDir.toPath())) {
                paths.filter(Files::isRegularFile)
                        .filter(path -> path.toString().endsWith(".java"))
                        .forEach(path -> {
                            try {
                                String content = Files.readString(path);
                                // Simple protection: Don't embed massive files
                                if (content.length() < 10000 && !content.isBlank()) {
                                    chunksToInsert.add(CodeChunk.builder()
                                            .repoUrl(repo.getRepoUrl())
                                            .filePath(path.toString().replace(tempDir.getAbsolutePath(), ""))
                                            .content(content)
                                            .build());
                                }
                            } catch (IOException e) {
                                log.warn("Skipping file: " + path);
                            }
                        });
            } catch (IOException e) {
                throw new RuntimeException(e);
            }

            log.info("Parsed {} files. Generating embeddings with Ollama...", chunksToInsert.size());

            int batchSize = 10;
            for (int i = 0; i < chunksToInsert.size(); i++) {
                CodeChunk chunk = chunksToInsert.get(i);

                // Call local Ollama
                float[] vector = embeddingModel.embed(chunk.getContent());
                chunk.setEmbedding(vector);

                // Save incrementally or saveAll at end
                if (i % batchSize == 0) {
                    log.info("Processed {}/{} chunks...", i, chunksToInsert.size());
                }
            }

            codeChunkRepository.saveAll(chunksToInsert);
            log.info("Job {} COMPLETED. Inserted {} vectors into PGVector.", jobId, chunksToInsert.size());
        } catch (Exception e) {
            log.error("Error processing repo", e);
        } finally {
            // 5. Cleanup (Step 15 in diagram)
            FileSystemUtils.deleteRecursively(tempDir);
        }
    }

    private List<Double> toDoubleList(float[] floatArray) {
        if (floatArray == null) return new ArrayList<>();
        return IntStream.range(0, floatArray.length)
                .mapToDouble(i -> floatArray[i])
                .boxed()
                .toList();
    }
}
