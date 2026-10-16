package com.github.hurdadd.domain;

// callback reports a state the payment has not reached yet; the PSP should retry once we catch up
public class OutOfOrderCallbackException extends RuntimeException {
    public OutOfOrderCallbackException(String message) {
        super(message);
    }
}
