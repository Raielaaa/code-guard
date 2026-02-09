package com.repo.guard.model.repo;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.Array;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "code_chunks")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CodeChunk {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    private String repoUrl;
    private String filePath;

    @Column(columnDefinition = "TEXT")
    private String content; // The actual code snippet

    // Postgres pgvector column
    // The dimension (e.g., 1536 for OpenAI) must match your model
    @Column(name = "embedding", columnDefinition = "vector(768)")
    @JdbcTypeCode(SqlTypes.VECTOR)
    private float[] embedding;
}