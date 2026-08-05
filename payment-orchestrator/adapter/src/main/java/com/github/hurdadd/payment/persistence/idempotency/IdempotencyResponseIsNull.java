package com.github.hurdadd.payment.persistence.idempotency;

import org.springframework.http.HttpStatus;

public class IdempotencyResponseIsNull extends RuntimeException {
    public IdempotencyResponseIsNull(String message) {
        super(message);
    }

    public IdempotencyResponseIsNull(String message, Throwable cause) {
        super(message , cause);
    }
}
