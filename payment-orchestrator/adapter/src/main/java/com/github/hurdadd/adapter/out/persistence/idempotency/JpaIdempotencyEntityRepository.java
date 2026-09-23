package com.github.hurdadd.adapter.out.persistence.idempotency;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;

public interface JpaIdempotencyEntityRepository extends JpaRepository<IdempotencyEntity, String> {

    @Modifying
    @Query(
            value = """
                    INSERT INTO idempotency_keys (idempotency_key, body_hash, response, created_at)
                    VALUES (:key, :bodyHash, NULL, :createdAt)
                    ON CONFLICT (idempotency_key) DO NOTHING
                    """,
            nativeQuery = true
    )
    int insertIfAbsent(
            @Param("key") String key,
            @Param("bodyHash") String bodyHash,
            @Param("createdAt") Instant createdAt
    );

    @Modifying
    @Query(
            value = """
                    UPDATE idempotency_keys
                    SET response = CAST(:response AS jsonb)
                    WHERE idempotency_key = :key
                    """,
            nativeQuery = true
    )
    int updateResponse(
            @Param("key") String key,
            @Param("response") String response
    );
}
