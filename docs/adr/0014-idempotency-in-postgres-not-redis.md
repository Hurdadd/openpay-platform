# ADR-0014: Idempotency keys live in Postgres, scoped per merchant, with a TTL

## Status
Accepted (PAY-303)

## Context
The idempotency key was a global primary key: two merchants sending the same key collided, rows were kept
forever, and a retry that arrived while the first request was still running hit a null response and
returned a 500.

## Decision
**Scope.** Primary key is `(merchant_id, idempotency_key)`. The key is the client's namespace, so it only
has meaning together with the merchant.

**TTL.** Every row has `expires_at` (`idempotency.ttl`, default 24h). `tryInsert` treats an expired row as
absent (deletes it, then inserts). `IdempotencyKeyCleaner` removes expired rows in batches of
`idempotency.cleanup.batch-size` every `idempotency.cleanup.interval`, so no run holds a long lock.

**In flight.** The key row is committed together with the payment (TX1); the response is written in TX2
after the PSP call. Between the two, the same key with the same body returns `InFlight` → **409** with
`Retry-After`, not 500. A different body is still a plain 409 (`Conflict`) without `Retry-After`.

**Why Postgres, not Redis.**
| | Postgres | Redis |
|---|---|---|
| Atomic with the payment | **Yes** — key row and payment commit in one transaction; no state where a payment exists without its key (or the reverse) | No — two systems, no shared transaction; a crash between them double-charges or loses the key |
| Durability | Survives restart and failover (WAL) | Depends on AOF/RDB config; eviction or flush forgets keys, which means a duplicate charge |
| Failure mode | Same DB the payment needs anyway | Extra dependency; the replay guard already fails open on Redis outage (ADR-0013), which is acceptable only because the state machine is the real safety net |
| Cost | One indexed insert per request, cheap at this volume | Faster, but the latency gain is irrelevant next to a 1s PSP call |

Redis stays for the callback replay guard (defence in depth); idempotency is a correctness guarantee for
money, so it sits in the same transactional store as the money.

## Consequences
- Legacy rows (no merchant) are parked under the nil UUID by migration V6 and expire after 24h.
- Postgres-specific SQL (`ON CONFLICT DO NOTHING`, `jsonb`) is only exercised against H2 in PostgreSQL mode;
  true concurrent-insert behaviour is not covered by a test (no Testcontainers in this repo).
