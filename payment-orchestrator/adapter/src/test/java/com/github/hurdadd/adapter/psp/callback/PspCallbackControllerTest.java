package com.github.hurdadd.adapter.psp.callback;

import com.github.hurdadd.adapter.security.RedisReplayGuard;
import com.github.hurdadd.application.port.in.PspCallbackHandler;
import com.github.hurdadd.domain.OutOfOrderCallbackException;
import com.github.hurdadd.domain.PaymentStatus;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.http.HttpStatus;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

// plain unit test: real controller + real RedisReplayGuard over an in-memory fake of Redis SETNX/DEL
class PspCallbackControllerTest {

    private static final byte[] BODY = "{\"pspReference\":\"ref-1\",\"status\":\"AUTHORIZED\"}".getBytes();

    private final Set<String> keys = new HashSet<>();
    private final HmacVerifier hmac = mock(HmacVerifier.class);
    private final PspCallbackHandler handler = mock(PspCallbackHandler.class);
    private final PspCallbackController controller;

    PspCallbackControllerTest() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        when(values.setIfAbsent(anyString(), anyString(), any(java.time.Duration.class)))
                .thenAnswer(i -> keys.add(i.getArgument(0)));
        when(redis.delete(anyString())).thenAnswer(i -> keys.remove(i.<String>getArgument(0)));
        when(hmac.verify(any(), anyLong(), any(), any(), any())).thenReturn(true);
        controller = new PspCallbackController(hmac, new RedisReplayGuard(redis, Clock.systemUTC()), handler, JsonMapper.builder().build());
    }

    private static long anyLong() {
        return org.mockito.ArgumentMatchers.anyLong();
    }

    private HttpStatus deliver() {
        return HttpStatus.valueOf(controller.receive("psp-a", "sig", Instant.now().getEpochSecond(), "nonce-1", BODY)
                .getStatusCode().value());
    }

    @Test
    void db_failure_on_first_delivery_does_not_burn_the_nonce() {
        doThrow(new IllegalStateException("db down")).doNothing().when(handler).handle(any(), any());

        assertThrows(IllegalStateException.class, this::deliver);

        assertEquals(HttpStatus.OK, deliver());
        verify(handler, org.mockito.Mockito.times(2)).handle(any(), any());
    }

    @Test
    void early_callback_gets_409_and_retry_with_same_nonce_is_processed() {
        doThrow(new OutOfOrderCallbackException("early")).doNothing().when(handler).handle(any(), any());

        assertEquals(HttpStatus.CONFLICT, deliver());
        assertEquals(HttpStatus.OK, deliver());
        verify(handler, org.mockito.Mockito.times(2)).handle(any(), any());
    }

    @Test
    void successful_delivery_then_same_nonce_is_replay_noop() {
        doNothing().when(handler).handle(any(), any());

        assertEquals(HttpStatus.OK, deliver());
        assertEquals(HttpStatus.OK, deliver());
        verify(handler, org.mockito.Mockito.times(1)).handle(any(), any());
    }
}
