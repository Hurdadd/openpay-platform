package com.github.hurdadd.adapter.out.persistence.payment.outbox;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface JpaPaymentOutboxEntityRepository extends JpaRepository<PaymentOutboxEntity, UUID> {

    @Query(
            value = """
                    SELECT *
                    FROM payment_outbox
                    WHERE published = FALSE
                    ORDER BY created_at ASC
                    LIMIT 100
                    FOR UPDATE SKIP LOCKED
                    """,
            nativeQuery = true
    )
    List<PaymentOutboxEntity> findUnpublished();
}
