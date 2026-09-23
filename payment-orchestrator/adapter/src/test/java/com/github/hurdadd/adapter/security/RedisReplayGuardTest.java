package com.github.hurdadd.adapter.security;

import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RedisReplayGuardTest {

    @Test
    void nonce_is_namespaced_by_psp() {
        Instant now = Instant.parse("2026-09-23T10:00:00Z");

        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        when(values.setIfAbsent(
                "replay:psp-a:nonce:same-nonce",
                "1",
                RedisReplayGuard.WINDOW
        )).thenReturn(true);

        RedisReplayGuard guard = new RedisReplayGuard(
                redis,
                Clock.fixed(now, ZoneOffset.UTC)
        );

        assertFalse(guard.isReplay("psp-a", "same-nonce", now));

        verify(values).setIfAbsent(
                "replay:psp-a:nonce:same-nonce",
                "1",
                RedisReplayGuard.WINDOW
        );
    }

    @Test
    void duplicate_nonce_for_same_psp_is_replay() {
        Instant now = Instant.parse("2026-09-23T10:00:00Z");

        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        when(values.setIfAbsent(
                "replay:psp-a:nonce:duplicate",
                "1",
                RedisReplayGuard.WINDOW
        )).thenReturn(false);

        RedisReplayGuard guard = new RedisReplayGuard(
                redis,
                Clock.fixed(now, ZoneOffset.UTC)
        );

        assertTrue(guard.isReplay("psp-a", "duplicate", now));
    }

    @Test
    void stale_timestamp_is_rejected_before_redis() {
        Instant now = Instant.parse("2026-09-23T10:00:00Z");

        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        RedisReplayGuard guard = new RedisReplayGuard(
                redis,
                Clock.fixed(now, ZoneOffset.UTC)
        );

        assertTrue(guard.isReplay(
                "psp-a",
                "nonce",
                now.minus(RedisReplayGuard.WINDOW).minusSeconds(1)
        ));
    }
}
