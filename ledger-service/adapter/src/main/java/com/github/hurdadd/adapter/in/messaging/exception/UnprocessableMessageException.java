package com.github.hurdadd.adapter.in.messaging.exception;

public class UnprocessableMessageException extends RuntimeException {
    public UnprocessableMessageException(String message) {
        super(message);
    }

    public UnprocessableMessageException(String message, Throwable cause) {
        super(message, cause);
    }
}
