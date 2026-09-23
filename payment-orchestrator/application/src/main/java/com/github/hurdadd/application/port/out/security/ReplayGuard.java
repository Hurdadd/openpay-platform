package com.github.hurdadd.application.port.out.security;

import java.time.Instant;

public interface ReplayGuard {
    boolean isReplay(String nonce , Instant timestamp );
}
