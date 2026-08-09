package com.github.hurdadd.adapter.in.persistence.idempotency.mapper;

public class ResponseSerializationException extends RuntimeException {
    public ResponseSerializationException(String message) {
        super(message);
    }

    public ResponseSerializationException(String message, Throwable cause) {
        super(message, cause);
    }
}
