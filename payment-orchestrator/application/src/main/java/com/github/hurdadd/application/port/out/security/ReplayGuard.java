package com.github.hurdadd.application.port.out.security;

import java.time.Instant;

public interface ReplayGuard {
    boolean isReplay(String pspId, String nonce, Instant timestamp);

    // un-claims a nonce when processing failed, so the PSP's retry with the same nonce is not mistaken for a replay
    void release(String pspId, String nonce);
}
