package com.github.hurdadd.adapter.in.persistence.idempotency;

public class IdempotencyResponseIsNull extends RuntimeException {
    public IdempotencyResponseIsNull(String message) {
        super(message);
    }

    public IdempotencyResponseIsNull(String message, Throwable cause) {
        super(message , cause);
    }
}
