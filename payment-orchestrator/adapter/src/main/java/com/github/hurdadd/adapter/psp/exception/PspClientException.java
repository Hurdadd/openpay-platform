package com.github.hurdadd.adapter.psp.exception;

public class PspClientException extends RuntimeException {
    public PspClientException(String message) {
        super(message);
    }
    public PspClientException(String message, Throwable cause) {
        super(message, cause);
    }
}
