package com.github.hurdadd.payment.persistence.idempotency;

import org.springframework.data.jpa.repository.JpaRepository;

public interface JpaIdempotencyEntityRepository extends JpaRepository<IdempotencyEntity, String> {
}
