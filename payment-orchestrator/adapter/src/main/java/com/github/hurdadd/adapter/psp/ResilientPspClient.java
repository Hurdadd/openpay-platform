package com.github.hurdadd.adapter.psp;

import com.github.hurdadd.adapter.psp.exception.PspClientException;
import com.github.hurdadd.adapter.psp.exception.PspException;
import com.github.hurdadd.application.port.out.psp.AuthorizationRequest;
import com.github.hurdadd.application.port.out.psp.PspClient;
import com.github.hurdadd.application.port.out.psp.PspResult;
import com.github.hurdadd.domain.PspReference;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.core.IntervalFunction;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import io.github.resilience4j.timelimiter.TimeLimiter;
import io.github.resilience4j.timelimiter.TimeLimiterConfig;
import jakarta.annotation.PreDestroy;
import lombok.Getter;

import java.time.Duration;
import java.util.concurrent.*;
import java.util.function.Supplier;

public class ResilientPspClient implements PspClient
{
    private final PspClient pspClient;
    private final Retry retry;
    @Getter
    private final CircuitBreaker circuitBreaker;
    private final ScheduledExecutorService scheduledThreadPool =
            Executors.newScheduledThreadPool(2);
    private final ExecutorService vtExecutor =
            Executors.newVirtualThreadPerTaskExecutor();
    private final TimeLimiter timeLimiter;


    public ResilientPspClient(PspClient pspClient) {
       RetryConfig retryConfig = RetryConfig.custom()
                .maxAttempts(5)
               .intervalFunction(IntervalFunction.ofExponentialRandomBackoff(Duration.ofMillis(100) , 0.2 , 0.5))
               .ignoreExceptions(PspClientException.class)
                .build();
        CircuitBreakerConfig circuitBreakerConfig = CircuitBreakerConfig.custom()
                .failureRateThreshold(50)
                .waitDurationInOpenState(Duration.ofSeconds(10))
                .permittedNumberOfCallsInHalfOpenState(10)
                .slidingWindow(10 , 10 , CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                .build();
        TimeLimiterConfig timeLimiterConfig = TimeLimiterConfig.custom()
                .timeoutDuration(Duration.ofMillis(500))
                .build();
        this.retry = Retry.of("pspClientRetry", retryConfig);
        this.circuitBreaker = CircuitBreaker.of("pspClientCircuitBreaker", circuitBreakerConfig);
        this.timeLimiter = TimeLimiter.of("pspLimiter",timeLimiterConfig);
        this.pspClient = pspClient;

    }

    @Override
    public PspResult authorize(AuthorizationRequest dto) {
        return callResilient(() -> pspClient.authorize(dto), "PSP authorization failed");
    }

    @Override
    public PspResult checkStatus(PspReference reference) {
        return callResilient(() -> pspClient.checkStatus(reference), "PSP status check failed");
    }

    private PspResult callResilient(Supplier<PspResult> call, String failureMessage) {
        Supplier<CompletableFuture<PspResult>> futureSupplier =
                () -> CompletableFuture.supplyAsync(call, vtExecutor);

        Callable<PspResult> timeLimited =
                TimeLimiter.decorateFutureSupplier(timeLimiter, futureSupplier);

        Callable<PspResult> withCircuitBreaker =
                CircuitBreaker.decorateCallable(circuitBreaker, timeLimited);

        Callable<PspResult> withRetry =
                Retry.decorateCallable(retry, withCircuitBreaker);

        try {
            return withRetry.call();
        } catch (Exception e) {
            throw new PspException(failureMessage, e);
        }
    }
    @PreDestroy
    void shutdown() {
        scheduledThreadPool.shutdown();
        vtExecutor.shutdown();
    }



}
