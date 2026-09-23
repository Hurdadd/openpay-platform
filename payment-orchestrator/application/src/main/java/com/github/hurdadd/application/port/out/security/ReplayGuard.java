package com.github.hurdadd.application.port.out.security;

import java.time.Instant;

public interface ReplayGuard {
    boolean isReplay(String pspId, String nonce, Instant timestamp);
}
