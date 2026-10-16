package com.github.hurdadd.adapter.security;

import com.github.hurdadd.application.port.out.security.ReplayGuard;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

@Component
public class RedisReplayGuard implements ReplayGuard {

    private static final Logger log = LoggerFactory.getLogger(RedisReplayGuard.class);

    static final Duration WINDOW = Duration.ofMinutes(5);

    private final StringRedisTemplate redis;
    private final Clock clock;

    public RedisReplayGuard(StringRedisTemplate redis, Clock clock) {
        this.redis = redis;
        this.clock = clock;
    }

    @Override
    public boolean isReplay(String pspId, String nonce, Instant timestamp) {
        if (isOutsideWindow(timestamp)) {
            return true;
        }

        try {
            Boolean inserted = redis.opsForValue().setIfAbsent(
                    nonceKey(pspId, nonce),
                    "1",
                    WINDOW
            );
            return !Boolean.TRUE.equals(inserted);
        } catch (DataAccessException e) {
            // fail-open (ADR-0013): HMAC + timestamp window + idempotent state machine still hold
            log.warn("Redis unavailable, replay check skipped for psp {}", pspId, e);
            return false;
        }
    }

    @Override
    public void release(String pspId, String nonce) {
        try {
            redis.delete(nonceKey(pspId, nonce));
        } catch (DataAccessException e) {
            log.warn("Could not release nonce for psp {}; PSP retry will be treated as replay until TTL", pspId, e);
        }
    }

    private boolean isOutsideWindow(Instant timestamp) {
        Instant now = clock.instant();
        return timestamp.isBefore(now.minus(WINDOW))
                || timestamp.isAfter(now.plus(WINDOW));
    }

    private String nonceKey(String pspId, String nonce) {
        return "replay:" + pspId + ":nonce:" + nonce;
    }
}
