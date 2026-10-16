package com.github.hurdadd.adapter.psp;

import java.time.Duration;

/**
 * Concrete resilience values for {@link ResilientPspClient}.
 *
 * <p>Framework-free on purpose: the adapter must not know where its values come
 * from. The composition root (bootstrap) is the only place that talks to Spring
 * ({@code @ConfigurationProperties}); it builds this record from
 * {@code application.yml} and hands it over. That is also what keeps this
 * adapter testable without Spring: tests construct their own settings.
 *
 * <p>There are no defaults here on purpose — a default is a hard-coded value
 * wearing a disguise. Every caller states its numbers explicitly.
 */
public record PspResilienceSettings(
        Duration timeout,
        int maxAttempts,
        Duration initialRetryInterval,
        double retryMultiplier,
        double retryJitter,
        Duration circuitBreakerWaitDuration
) {
}
