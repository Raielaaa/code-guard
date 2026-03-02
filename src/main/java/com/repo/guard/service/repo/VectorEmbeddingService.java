package com.repo.guard.service.repo;

import com.repo.guard.model.repo.CodeChunk;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.stereotype.Service;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class VectorEmbeddingService {
    private final EmbeddingModel embeddingModel;
    //  splitter that guarantees chunks are <= 512 tokens, with 300 token overlap, and a max of 10 splits per file
    private final TokenTextSplitter textSplitter = new TokenTextSplitter(512, 300, 10, 50, true);

    /**
     * splits raw text into smaller document segments using intelligent token boundaries
     *
     * @param content
     * @return
     */
    public List<Document> splitText(String content) {
        Document sourceDoc = new Document(content);
        return textSplitter.apply(List.of(sourceDoc));
    }

    /**
     * generates embeddings for a list of chunks sequentially with optimistic execution
     *
     * @param chunksToInsert
     */
    public void generateEmbeddingsForChunks(List<CodeChunk> chunksToInsert) {
        log.info("Parsed {} chunks. Generating embeddings...", chunksToInsert.size());

        //  warm up the model with a test embedding to ensure it is fully loaded into unified memory
        try {
            log.info("Warming up embedding model...");
            embeddingModel.embed("model warmup test");
            log.info("Model warmup complete");
        } catch (Exception e) {
            log.warn("Model warmup failed, continuing anyway", e);
        }

        int batchSize = 10;

        //  process chunks optimistically running as fast as possible and only delaying if the model fails
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
     *
     * @param content
     * @param chunkIndex
     * @param maxRetries
     * @return
     */
    public float[] generateEmbeddingWithRetry(String content, int chunkIndex, int maxRetries) {
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
}
