package com.repo.guard.model.repo;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;
import java.util.UUID;

@Repository
public interface CodeChunkRepository extends JpaRepository<CodeChunk, UUID> {

    /**
     * Finds the most similar code chunks using Cosine Distance.
     * * The operator '<=>' is the pgvector specific operator for "Cosine Distance".
     * CAST(?1 AS vector) converts the Java float[] into a Postgres Vector.
     * * @param embedding The vector of the user's query
     * @param limit How many results to return
     * @return List of matching code chunks
     */
    @Query(value = "SELECT * FROM code_chunks ORDER BY embedding <=> cast(?1 as vector) LIMIT ?2", nativeQuery = true)
    List<CodeChunk> findSimilarChunks(float[] embedding, int limit);

    @Query(value = """
            SELECT * FROM code_chunks 
            WHERE repo_url = ?3 
            ORDER BY embedding <=> cast(?1 as vector)
            LIMIT ?2
            """, nativeQuery = true)
    List<CodeChunk> findSimilarChunksByRepo(float[] embedding, int limit, String repoUrl);

    List<CodeChunk> findByRepoUrl(String repoUrl);

    @Transactional
    void deleteByRepoUrl(String repoUrl);

    @Transactional
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = "DELETE FROM code_chunks WHERE repo_url = :repoUrl AND file_path LIKE CONCAT(:filePath, '%')", nativeQuery = true)
    void deleteByRepoUrlAndFilePathStartingWith(@Param("repoUrl") String repoUrl, @Param("filePath") String filePath);
}