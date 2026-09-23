# ADR-0004: Programmatic Resilience4j decorator, not annotations

## Status
Accepted (PAY-3), extended (PAY-4: `checkStatus` reuses the same decorator chain)

## Context
`PspClient` calls are the one true external I/O boundary calling out to an unreliable third party (a
bank/PSP). PAY-3 needed retry, circuit-breaking, and timeout behavior around it. Resilience4j supports
both a declarative style (`@Retry`, `@CircuitBreaker` annotations + Spring AOP) and a programmatic style
(manually composing `Retry`/`CircuitBreaker`/`TimeLimiter` decorators around a `Callable`).

## Decision
`ResilientPspClient` wraps a raw `PspClient` and manually composes the decorator chain:

```
Retry.decorateCallable(retry,
    CircuitBreaker.decorateCallable(circuitBreaker,
        TimeLimiter.decorateFutureSupplier(timeLimiter, futureSupplier)))
```

Order matters and is deliberate:
- **TimeLimiter innermost** — it bounds a single attempt's duration.
- **CircuitBreaker next** — it must see every attempt (including ones that get retried) to count
  failures correctly; if it were outside Retry, one logical call = multiple CB observations, which is
  what we want (a flapping PSP should open the breaker based on real attempt outcomes).
- **Retry outermost** — so retry decides whether to re-run the whole (timed, breaker-guarded) attempt.

Chosen programmatic over declarative specifically **for learning** — annotations would hide the ordering
decision above inside Spring AOP proxy semantics, which this project's stated goal (refresh Java/Spring
fundamentals by building things by hand) works against. Declarative would be the pragmatic choice for
a production team without that constraint.

The PSP call itself runs on a virtual-thread-per-task executor (`Executors.newVirtualThreadPerTaskExecutor()`),
not a fixed pool — chosen because the call is I/O-bound (blocking on network), and virtual threads avoid
pool-sizing tuning for that workload.

## PAY-4 extension
The callback/reconciliation work added `PspClient.checkStatus(PspReference)` — the reconciler's "ask the
PSP directly" call. It goes through the exact same three-decorator chain as `authorize()` (refactored into
a shared private `callResilient(Supplier<PspResult>, String)` method), so a flapping PSP during
reconciliation gets the same retry/backoff/circuit-breaking protection as during the original
authorization — and, not incidentally, `ResilientPspClient`'s circuit breaker is shared state, so if
`authorize()` calls have already tripped the breaker, `checkStatus()` calls fail fast too instead of
piling onto an already-struggling PSP.

## Consequences
- Config values (max attempts, backoff, breaker thresholds/windows, timeout) are still hardcoded in
  `ResilientPspClient`'s constructor rather than externalized to `application.yml` — logged as a known
  debt from PAY-3, not yet paid down.
- `PspException` (retryable) vs `PspClientException` (permanent, e.g. bad request) are distinguished so
  `Retry`'s `ignoreExceptions` can skip retrying a 4xx-shaped failure — retrying a validation error
  wastes attempts and delays the real failure signal.
