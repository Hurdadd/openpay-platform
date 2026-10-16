package com.github.hurdadd.adapter.psp;

import com.github.hurdadd.adapter.psp.exception.PspClientException;
import com.github.hurdadd.adapter.psp.exception.PspException;
import com.github.hurdadd.application.port.out.psp.AuthorizationRequest;
import com.github.hurdadd.application.port.out.psp.PspClient;
import com.github.hurdadd.application.port.out.psp.PspResult;
import com.github.hurdadd.domain.PspReference;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.core.IntervalFunction;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import io.github.resilience4j.timelimiter.TimeLimiter;
import io.github.resilience4j.timelimiter.TimeLimiterConfig;
import jakarta.annotation.PreDestroy;
import lombok.Getter;

import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Supplier;

public class ResilientPspClient implements PspClient {

    private final PspClient pspClient;
    private final Retry retry;

    @Getter
    private final CircuitBreaker circuitBreaker;

    private final ExecutorService vtExecutor = Executors.newVirtualThreadPerTaskExecutor();
    private final TimeLimiter timeLimiter;

    public ResilientPspClient(PspClient pspClient, PspResilienceSettings settings) {
        RetryConfig retryConfig = RetryConfig.custom()
                .maxAttempts(settings.maxAttempts())
                .intervalFunction(
                        IntervalFunction.ofExponentialRandomBackoff(
                                settings.initialRetryInterval(),
                                settings.retryMultiplier(),
                                settings.retryJitter()
                        )
                )
                // Never retried:
                // - PspClientException: a 4xx-class answer is definitive; re-asking
                //   changes nothing, it only spends time and load
                // - CallNotPermittedException: the breaker is OPEN and just told us
                //   not to ask; retrying it would defeat the fail-fast the breaker
                //   exists to provide
                .ignoreExceptions(PspClientException.class, CallNotPermittedException.class)
                .build();

        CircuitBreakerConfig circuitBreakerConfig = CircuitBreakerConfig.custom()
                .failureRateThreshold(50)
                .waitDurationInOpenState(settings.circuitBreakerWaitDuration())
                .permittedNumberOfCallsInHalfOpenState(10)
                .slidingWindow(10, 10, CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                // A 4xx-class answer is the PSP doing its job correctly: the breaker
                // measures PSP health, not transaction approval rates. Ten declined
                // cards in a row must not take a healthy PSP out of service.
                .ignoreExceptions(PspClientException.class , CallNotPermittedException.class)
                .build();

        TimeLimiterConfig timeLimiterConfig = TimeLimiterConfig.custom()
                .timeoutDuration(settings.timeout())
                .build();

        this.retry = Retry.of("pspClientRetry", retryConfig);
        this.circuitBreaker = CircuitBreaker.of("pspClientCircuitBreaker", circuitBreakerConfig);
        this.timeLimiter = TimeLimiter.of("pspLimiter", timeLimiterConfig);
        this.pspClient = pspClient;
    }

    @Override
    public PspResult authorize(AuthorizationRequest dto) {

        return callResilientWithoutRetry(() -> pspClient.authorize(dto), "PSP authorization failed");
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

    private PspResult callResilientWithoutRetry(Supplier<PspResult> call, String failureMessage) {
        Supplier<CompletableFuture<PspResult>> futureSupplier =
                () -> CompletableFuture.supplyAsync(call, vtExecutor);

        Callable<PspResult> timeLimited =
                TimeLimiter.decorateFutureSupplier(timeLimiter, futureSupplier);

        Callable<PspResult> withCircuitBreaker =
                CircuitBreaker.decorateCallable(circuitBreaker, timeLimited);

        try {
            return withCircuitBreaker.call();
        } catch (Exception e) {
            throw new PspException(failureMessage, e);
        }
    }

    @PreDestroy
    void shutdown() {
        vtExecutor.shutdown();
    }
}
