package com.github.hurdadd.adapter.out.persistence.idempotency;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

import java.io.Serializable;
import java.util.UUID;

@Embeddable
public record IdempotencyId(
        @Column(name = "merchant_id", nullable = false) UUID merchantId,
        @Column(name = "idempotency_key", nullable = false) String idempotencyKey
) implements Serializable {
}
