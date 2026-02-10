package com.repo.guard.controller.auth;

import com.repo.guard.dto.ApiResponse;
import com.repo.guard.dto.RepoIngestionRequestDto;
import com.repo.guard.dto.ResponseDto;
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

    @PostMapping("/ingest")
    public ResponseEntity<ApiResponse> validateAndPushRepo(@RequestBody RepoIngestionRequestDto repo) {
        repoIngestionWorkerService.ingestRepositoryAsync(
                repo,
                UUID.randomUUID().toString()
        );

        return ResponseEntity.accepted().body(new ApiResponse(
                "Job accepted",
                null
        ));
    }
}
