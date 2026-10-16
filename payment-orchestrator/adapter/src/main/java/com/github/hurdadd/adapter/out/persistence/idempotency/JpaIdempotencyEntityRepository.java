package com.github.hurdadd.adapter.out.persistence.idempotency;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.UUID;

public interface JpaIdempotencyEntityRepository extends JpaRepository<IdempotencyEntity, IdempotencyId> {

    @Modifying
    @Query(
            value = """
                    INSERT INTO idempotency_keys (merchant_id, idempotency_key, body_hash, response, created_at, expires_at)
                    VALUES (:merchantId, :key, :bodyHash, NULL, :createdAt, :expiresAt)
                    ON CONFLICT DO NOTHING
                    """,
            nativeQuery = true
    )
    int insertIfAbsent(
            @Param("merchantId") UUID merchantId,
            @Param("key") String key,
            @Param("bodyHash") String bodyHash,
            @Param("createdAt") Instant createdAt,
            @Param("expiresAt") Instant expiresAt
    );

    // an expired key is treated as absent; removing it first lets insertIfAbsent claim the slot
    @Modifying
    @Query(
            value = """
                    DELETE FROM idempotency_keys
                    WHERE merchant_id = :merchantId AND idempotency_key = :key AND expires_at < :now
                    """,
            nativeQuery = true
    )
    int deleteIfExpired(
            @Param("merchantId") UUID merchantId,
            @Param("key") String key,
            @Param("now") Instant now
    );

    @Modifying
    @Query(
            value = """
                    DELETE FROM idempotency_keys
                    WHERE (merchant_id, idempotency_key) IN (
                        SELECT merchant_id, idempotency_key FROM idempotency_keys
                        WHERE expires_at < :now
                        LIMIT :batchSize
                    )
                    """,
            nativeQuery = true
    )
    int deleteExpiredBatch(@Param("now") Instant now, @Param("batchSize") int batchSize);

    @Modifying
    @Query(
            value = """
                    UPDATE idempotency_keys
                    SET response = CAST(:response AS jsonb)
                    WHERE merchant_id = :merchantId AND idempotency_key = :key
                    """,
            nativeQuery = true
    )
    int updateResponse(
            @Param("merchantId") UUID merchantId,
            @Param("key") String key,
            @Param("response") String response
    );
}
