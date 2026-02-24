package com.repo.guard.controller.ai;

import com.repo.guard.dto.ApiResponse;
import com.repo.guard.dto.ChatRequestDto;
import com.repo.guard.dto.ChatResponseDto;
import com.repo.guard.dto.RepoIngestionRequestDto;
import com.repo.guard.service.chat.ChatService;
import com.repo.guard.service.repo.RepoIngestionWorkerService;
import lombok.AllArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping(path = "${repo.path}")
@AllArgsConstructor
public class RepoController {
    private final RepoIngestionWorkerService repoIngestionWorkerService;
    private final ChatService chatService;

    /**
     * accepts a repository ingestion request and queues it for asynchronous processing
     *
     * @param repo
     * @return
     */
    @PostMapping("/ingest")
    public ResponseEntity<ApiResponse> validateAndPushRepo(@RequestBody RepoIngestionRequestDto repo) {
        //  start the ingestion process asynchronously with a generated unique job id
        repoIngestionWorkerService.ingestRepositoryAsync(repo, UUID.randomUUID().toString());

        //  return an accepted response immediately so the client isn't blocked waiting for ingestion
        return ResponseEntity.accepted().body(new ApiResponse("Job accepted", null));
    }

    /**
     * processes a chat request against the vector database and returns the ai generated response
     *
     * @param request
     * @return
     */
    @PostMapping("/chat")
    public ResponseEntity<ChatResponseDto> chat(@RequestBody ChatRequestDto request) {
        //  pass the request to the chat service and return the analyzed result
        return ResponseEntity.ok(chatService.askQuestion(request));
    }
}