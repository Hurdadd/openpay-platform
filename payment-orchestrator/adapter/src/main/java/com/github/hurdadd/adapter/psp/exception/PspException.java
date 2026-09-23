package com.github.hurdadd.adapter.psp.exception;

public class PspException extends RuntimeException {
    public PspException(String message) {
        super(message);
    }

    public PspException(String message, Throwable cause) {
        super(message, cause);
    }
}
