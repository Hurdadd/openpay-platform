package com.github.bootstrap;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.Objects;

/**
 * Spring binding for the {@code psp.resilience} block in application.yml.
 *
 * <p>Lives in the composition root, not in the adapter: the adapter takes a
 * framework-free {@link com.github.hurdadd.adapter.psp.PspResilienceSettings}
 * and never sees Spring.
 *
 * <p>A missing key is a boot-time failure, on purpose: an NPE three hours into
 * production traffic is a much worse way to learn that a key was renamed.
 */
@ConfigurationProperties(prefix = "psp.resilience")
public record PspResilienceProperties(
        Duration timeout,
        Integer maxAttempts,
        Duration initialRetryInterval,
        Double retryMultiplier,
        Double retryJitter,
        Duration circuitBreakerWaitDuration
) {

    public PspResilienceProperties {
        require(timeout, "psp.resilience.timeout");
        require(maxAttempts, "psp.resilience.max-attempts");
        require(initialRetryInterval, "psp.resilience.initial-retry-interval");
        require(retryMultiplier, "psp.resilience.retry-multiplier");
        require(retryJitter, "psp.resilience.retry-jitter");
        require(circuitBreakerWaitDuration, "psp.resilience.circuit-breaker-wait-duration");
    }

    private static void require(Object value, String key) {
        Objects.requireNonNull(value, () -> "Missing configuration: " + key);
    }
}
