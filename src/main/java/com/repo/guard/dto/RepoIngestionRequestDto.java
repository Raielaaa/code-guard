package com.repo.guard.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class RepoIngestionRequestDto {
    private String repoUrl;
    private String repoUsername;
    private String repoAccessToken;
}
