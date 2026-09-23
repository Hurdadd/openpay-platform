package com.github.hurdadd.adapter.security;

import com.github.hurdadd.application.port.out.security.ReplayGuard;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

@Component
public class RedisReplayGuard implements ReplayGuard {

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

        Boolean inserted = redis.opsForValue().setIfAbsent(
                nonceKey(pspId, nonce),
                "1",
                WINDOW
        );

        return !Boolean.TRUE.equals(inserted);
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
