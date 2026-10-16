# ADR-0015: Bounded database waits — fail fast with 503

## Status
Accepted (PAY-305)

## Context
Failure injection (Postgres latency 2 s) showed the create-payment API had no server-side bound: with a
10-connection pool, 24 concurrent requests each ran several 2 s round-trips and callers waited 20 s+ or gave up
client-side. Concurrent duplicate callbacks also surfaced an unmapped optimistic-lock failure as `500`.

## Decision
- Hikari `connection-timeout` 1 s (pool wait), JDBC `socketTimeout` 5 s / `connectTimeout` 2 s (black-holed socket).
- The `TxRunner` used by the use cases has a 2 s transaction timeout (`tx.timeout-seconds`). **Not** a global
  `spring.transaction.default-timeout`: `OutboxPublisher` legitimately holds its transaction open while waiting for
  Kafka, and a global timeout would roll back after a successful send and republish forever.
- `DataAccessException` / `TransactionException` map to `503` + `Retry-After: 1` (`ProblemDetailAdvice`). A failed
  request committed nothing, so retrying with the same `Idempotency-Key` is safe; a request that fails after the PSP
  call leaves a `CREATED` payment, which the reconciler already covers.
- `OptimisticLockingFailureException` in the callback controller releases the nonce and answers `409`, like an early callback.

## Consequences
- A slow database sheds load instead of queueing it; the pool drains as soon as the database recovers (asserted by the e2e test).
- 2 s is above the happy-path budget (README) but below the 3 s SLA; revisit if request-path transactions grow.
