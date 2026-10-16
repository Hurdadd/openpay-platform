package com.github.hurdadd.adapter.out.persistence.payment.outbox;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

@Repository
public interface JpaPaymentOutboxEntityRepository extends JpaRepository<PaymentOutboxEntity, UUID> {

    /**
     * Rows nobody is working on: unpublished and either never claimed or claimed before {@code leaseExpiredBefore}.
     * SKIP LOCKED lets several publishers claim disjoint batches at the same time.
     */
    @Query(
            value = """
                    SELECT *
                    FROM payment_outbox
                    WHERE published = FALSE
                      AND (claimed_at IS NULL OR claimed_at < :leaseExpiredBefore)
                    ORDER BY created_at ASC
                    LIMIT 100
                    FOR UPDATE SKIP LOCKED
                    """,
            nativeQuery = true
    )
    List<PaymentOutboxEntity> findClaimable(@Param("leaseExpiredBefore") Instant leaseExpiredBefore);

    @Modifying
    @Query("UPDATE PaymentOutboxEntity o SET o.published = TRUE, o.publishedAt = :at WHERE o.id = :id")
    int markPublished(@Param("id") UUID id, @Param("at") Instant at);

    @Modifying
    @Query("UPDATE PaymentOutboxEntity o SET o.claimedAt = NULL WHERE o.id IN :ids AND o.published = FALSE")
    int release(@Param("ids") Collection<UUID> ids);
}
