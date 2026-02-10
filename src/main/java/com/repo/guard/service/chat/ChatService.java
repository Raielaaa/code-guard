package com.repo.guard.service.chat;

import com.repo.guard.dto.ChatRequestDto;
import com.repo.guard.dto.ChatResponseDto;
import com.repo.guard.model.repo.CodeChunk;
import com.repo.guard.model.repo.CodeChunkRepository;
import io.netty.util.internal.StringUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.jgit.util.StringUtils;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.stereotype.Service;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class ChatService {
    private final CodeChunkRepository codeChunkRepository;
    private final EmbeddingModel embeddingModel;
    private final ChatModel chatModel;

    //  process 50 chunks at a time (safe for 3b model memory)
    private static final int BATCH_SIZE = 50;

    /**
     * handles the chat request by determining the appropriate search strategy
     *
     * @param request
     * @return chat response with answer and sources
     */
    public ChatResponseDto askQuestion(ChatRequestDto request) {
        log.info("Analyzing question: {}", request.getQuestion());

        //  case 1: repo-specific question -> must scan everything to ensure 100% coverage
        if (!StringUtils.isEmptyOrNull(request.getRepoUrl())) return performFullRepoScan(request);

        //  case 2: global question (no repo specified) -> fallback to standard vector search
        return performGlobalVectorSearch(request);
    }

    /**
     * performs a full scan of the specified repository using a map-reduce approach to ensure comprehensive coverage
     *
     * @param request
     * @return
     */
    private ChatResponseDto performFullRepoScan(ChatRequestDto request) {
        log.info("Starting FULL SCAN for repo: {}", request.getRepoUrl());
        //  retrieve every chunk for this repo to ensure 100% coverage
        List<CodeChunk> allChunks = codeChunkRepository.findAllByRepoUrl(request.getRepoUrl());
        //  if no chunks found, return a message indicating the repo may not have been ingested yet
        if (allChunks.isEmpty()) return new ChatResponseDto("No code found for this repository. Has it been ingested?", List.of());

        log.info("Scanning {} total file chunks...", allChunks.size());
        //  containers to hold findings and relevant file paths from the map phase
        StringBuilder aggregatedFindings = new StringBuilder();
        List<String> relevantSources = new ArrayList<>();

        //  execute the map phase: iterate through all chunks in batches
        processBatches(allChunks, request.getQuestion(), aggregatedFindings, relevantSources);

        //  execute the reduce phase: summarize findings into a final answer
        return generateFinalSummary(aggregatedFindings, relevantSources, request.getQuestion());
    }

    /**
     * processes the code chunks in batches, asking the model to identify relevant information for each batch and accumulating findings
     *
     * @param allChunks
     * @param question
     * @param aggregatedFindings
     * @param relevantSources
     */
    private void processBatches(List<CodeChunk> allChunks, String question, StringBuilder aggregatedFindings, List<String> relevantSources) {
        //  loop through all chunks by batch_size
        for (int i = 0; i < allChunks.size(); i += BATCH_SIZE) {
            //  calculate the end index for the current batch
            int end = Math.min(allChunks.size(), i + BATCH_SIZE);
            List<CodeChunk> batch = allChunks.subList(i, end);

            //  analyze the current batch
            String batchResponse = analyzeBatch(batch, question);

            //  only keep batches that contain relevant info (filter out "NO_MATCH" responses)
            if (!batchResponse.contains("NO_MATCH")) {
                log.info("Found relevant info in batch {}/{}", (i / BATCH_SIZE) + 1, (allChunks.size() / BATCH_SIZE) + 1);
                aggregatedFindings.append("\n--- Findings from Batch ").append(i).append(" ---\n");
                aggregatedFindings.append(batchResponse).append("\n");
                //  collect file paths for the sources list
                relevantSources.addAll(batch.stream().map(CodeChunk::getFilePath).toList());
            }
        }
    }

    /**
     * analyzes a batch of code chunks to determine if they contain relevant information for the user's question,
     * returning extracted details or a negative signal
     *
     * @param batch
     * @param question
     * @return
     */
    private String analyzeBatch(List<CodeChunk> batch, String question) {
        //  combine code chunks into a single context string for the prompt
        String batchContext = batch.stream()
                .map(c -> "File: " + c.getFilePath() + "\nCode:\n" + c.getContent())
                .collect(Collectors.joining("\n\n"));

        //  ask model: "does this specific batch contain the answer?"
        String mapPrompt = """
                Analyze this code batch for the user's question: "%s"
                
                If the code is RELEVANT, extract the specific details.
                If the code is NOT RELEVANT, simply say "NO_MATCH".
                
                CODE BATCH:
                %s
                """.formatted(question, batchContext);

        //  call the model and return its response
        return chatModel.call(mapPrompt);
    }

    /**
     * synthesizes the aggregated findings from all batches into a final answer for the user, along with a deduplicated list of source files
     *
     * @param aggregatedFindings
     * @param relevantSources
     * @param question
     * @return
     */
    private ChatResponseDto generateFinalSummary(StringBuilder aggregatedFindings, List<String> relevantSources, String question) {
        //  if no findings were collected from any batch, return early
        if (StringUtil.isNullOrEmpty(aggregatedFindings.toString()))
            return new ChatResponseDto("I scanned the entire repository but found no code matching your question.", List.of());

        //  construct the reduce prompt to synthesize all batch findings
        String reducePrompt = """
                You are a Senior Software Engineer. 
                I have scanned the entire repository and collected the following relevant details.
                Synthesize them into a clear, final answer for the user.
                
                USER QUESTION: %s
                
                ALL FINDINGS FROM REPO:
                %s
                """.formatted(question, aggregatedFindings.toString());

        //  call the model to get the final answer
        String finalAnswer = chatModel.call(reducePrompt);

        //  return final answer with deduplicated list of source files
        return new ChatResponseDto(finalAnswer, relevantSources.stream().distinct().toList());
    }

    /**
     * performs a global vector search across all repositories when no specific repo is provided,
     * returning the best answer based on the most similar code chunks
     *
     * @param request
     * @return
     */
    private ChatResponseDto performGlobalVectorSearch(ChatRequestDto request) {
        log.info("Repo URL missing. Performing global vector search.");

        //  generate embedding for the user question
        float[] queryVector = embeddingModel.embed(request.getQuestion());
        //  find top 10 most similar chunks across all repositories
        List<CodeChunk> similarChunks = codeChunkRepository.findSimilarChunks(queryVector, 10);

        //  if db is empty or no matches found, return early to save ai tokens
        if (similarChunks.isEmpty()) return new ChatResponseDto("I couldn't find any relevant code in the database.", List.of());

        //  build context from the found chunks
        String context = similarChunks.stream()
                .map(CodeChunk::getContent)
                .collect(Collectors.joining("\n\n"));

        //  ask the model to answer based on the retrieved context
        String prompt = "Answer based on context:\n" + context + "\n\nQuestion: " + request.getQuestion();
        String answer = chatModel.call(prompt);

        //  return answer and list of source files
        return new ChatResponseDto(answer, similarChunks.stream().map(CodeChunk::getFilePath).toList());
    }
}