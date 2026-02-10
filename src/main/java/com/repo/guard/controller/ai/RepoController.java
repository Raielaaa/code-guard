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

    @PostMapping("/ingest")
    public ResponseEntity<ApiResponse> validateAndPushRepo(@RequestBody RepoIngestionRequestDto repo) {
        repoIngestionWorkerService.ingestRepositoryAsync(repo, UUID.randomUUID().toString());
        return ResponseEntity.accepted().body(new ApiResponse("Job accepted", null));
    }

    @PostMapping("/chat")
    public ResponseEntity<ChatResponseDto> chat(@RequestBody ChatRequestDto request) {
        return ResponseEntity.ok(chatService.askQuestion(request));
    }
}
