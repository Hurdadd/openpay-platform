package com.github.hurdadd.adapter.in.persistence.idempotency;

import org.springframework.data.jpa.repository.JpaRepository;

public interface JpaIdempotencyEntityRepository extends JpaRepository<IdempotencyEntity, String> {
}
