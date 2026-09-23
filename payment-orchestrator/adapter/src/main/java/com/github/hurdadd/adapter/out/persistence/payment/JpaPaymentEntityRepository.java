package com.github.hurdadd.adapter.out.persistence.payment;

import com.github.hurdadd.domain.PaymentStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface JpaPaymentEntityRepository extends JpaRepository<PaymentEntity, UUID> {

    Optional<PaymentEntity> findByPspReference(String pspReference);

    @Query("""
            SELECT p FROM PaymentEntity p
            WHERE p.status = :status
              AND p.createdAt <= :olderThan
              AND p.createdAt >= :newerThan
            """)
    List<PaymentEntity> findStuck(@Param("status") PaymentStatus status,
                                   @Param("olderThan") Instant olderThan,
                                   @Param("newerThan") Instant newerThan);
}
