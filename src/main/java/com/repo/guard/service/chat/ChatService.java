package com.repo.guard.service.chat;

import com.repo.guard.dto.ChatRequestDto;
import com.repo.guard.dto.ChatResponseDto;
import com.repo.guard.model.repo.CodeChunk;
import com.repo.guard.model.repo.CodeChunkRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.jgit.util.StringUtils;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.stereotype.Service;
import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class ChatService {
    private final CodeChunkRepository codeChunkRepository;
    private final EmbeddingModel embeddingModel;
    private final ChatModel chatModel;

    //  limit the scan to the top 15 most relevant chunks to ensure < 1 minute execution
    //  pgvector is highly accurate; if it's not in the top 15, it's not in the repo.
    private static final int MAX_CHUNKS_TO_SCAN = 50;

    /**
     * handles the chat request by determining the appropriate search strategy
     *
     * @param request
     * @return chat response with answer and sources
     */
    public ChatResponseDto askQuestion(ChatRequestDto request) {
        log.info("Analyzing question: {}", request.getQuestion());

        //  case 1: repo-specific question -> must scan everything to ensure 100% coverage
        if (!StringUtils.isEmptyOrNull(request.getRepoUrl())) return performRepoSpecificVectorSearch(request);

        //  case 2: global question (no repo specified) -> fallback to standard vector search
        return performGlobalVectorSearch(request);
    }

    /**
     * perform an optimized vector search strictly within the requested repository
     *
     * @param request
     * @return
     */
    private ChatResponseDto performRepoSpecificVectorSearch(ChatRequestDto request) {
        log.info("Starting DIRECT VECTOR SCAN for repo: {}", request.getRepoUrl());

        //  generate embedding for the user question
        float[] queryVector = embeddingModel.embed(request.getQuestion());

        //  retrieve only the top 5 most similar chunks from the specified repository
        List<CodeChunk> topChunks = codeChunkRepository.findSimilarChunksByRepo(queryVector, MAX_CHUNKS_TO_SCAN, request.getRepoUrl());

        //  if no chunks found, return a message indicating the repo may not have been ingested yet
        if (topChunks.isEmpty()) return new ChatResponseDto("No code found for this repository. Has it been ingested?", List.of());

        log.info("Found top {} relevant chunks. Generating final answer...", topChunks.size());

        //  build context directly from the found chunks
        String context = topChunks.stream()
                .map(c -> "File: " + c.getFilePath() + "\nCode:\n" + c.getContent())
                .collect(Collectors.joining("\n\n"));

        //  ask the model to answer based on the retrieved context
        String prompt = """
            You are an Expert Software Engineer analyzing a codebase.
            Answer the QUESTION using ONLY the provided CONTEXT. 
            
            INSTRUCTIONS:
            1. The codebase may be in any language or framework.
            2. You MUST deduce features, architecture, and functionality by examining file paths, class names, variable names, comments, and code logic.
            3. Synthesize these technical clues into a clear, direct answer.
            4. Do not guess or invent features. If the answer cannot be logically deduced from the provided CONTEXT, reply exactly with: "I do not have enough context to answer this."
            5. Do not explain the code line-by-line. Just state the facts.
            
            CONTEXT:
            %s
            
            QUESTION: %s
            ANSWER:""".formatted(context, request.getQuestion());

        String answer = chatModel.call(prompt);

        //  return answer and deduplicated list of source files
        return new ChatResponseDto(answer, topChunks.stream().map(CodeChunk::getFilePath).distinct().toList());
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
                .map(c -> "File: " + c.getFilePath() + "\nCode:\n" + c.getContent())
                .collect(Collectors.joining("\n\n"));

        //  ask the model to answer based on the retrieved context
        String prompt = "Answer based on context:\n" + context + "\n\nQuestion: " + request.getQuestion();
        String answer = chatModel.call(prompt);

        //  return answer and list of source files
        return new ChatResponseDto(answer, similarChunks.stream().map(CodeChunk::getFilePath).distinct().toList());
    }
}