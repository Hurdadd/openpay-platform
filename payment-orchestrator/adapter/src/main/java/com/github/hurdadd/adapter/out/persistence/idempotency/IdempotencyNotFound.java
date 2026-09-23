package com.github.hurdadd.adapter.out.persistence.idempotency;

public class IdempotencyNotFound extends RuntimeException {
    public IdempotencyNotFound(String message) {
        super(message);
    }

    public IdempotencyNotFound(String message, Throwable cause) {
        super(message, cause);
    }
}
