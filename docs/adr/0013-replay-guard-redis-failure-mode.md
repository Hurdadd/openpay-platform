# ADR-0013: Callback ordering and Redis outage — fail-open replay guard

## Status
Accepted (PAY-301)

## Context
Callbacks are at-least-once and unordered. Two defects existed:
1. The nonce was claimed before processing. If processing failed (e.g. DB down) the PSP retried with the
   same nonce, which looked like a replay and was acknowledged with 200 — the callback was lost.
2. A callback ahead of our state (`SETTLED` while still `PENDING_PSP`) hit "transition not allowed" and was
   dropped with 200, same as a stale one.

## Decision
**Ordering.** Compare the reported status with the current one:

| Case | Rule | Response |
|---|---|---|
| same status | duplicate | 200, no-op |
| legal transition | apply | 200 |
| not legal now, but reachable later (`PaymentStatus.canReach`) — **early** | `OutOfOrderCallbackException` | **409**, PSP retries |
| not reachable (already past it, or conflicting terminal) — **stale** | no-op | 200 |

**Nonce.** Still claimed atomically up front (`SETNX`, so concurrent duplicates cannot both run), but
`ReplayGuard.release` removes it when the handler throws or answers 409. The body is parsed before the claim
so a 400 never burns a nonce.

**Redis down: fail-open.** `RedisReplayGuard` logs a warning and treats the callback as not-a-replay.
Reason: the nonce check is defence in depth. Authenticity is HMAC, freshness is the 5-minute timestamp
window, and business safety is the idempotent state machine, so a replay is a no-op on the payment. Failing
closed would turn a cache outage into a payment-callback outage plus PSP retry storms.
Residual risk: while Redis is down, a captured request can be re-submitted inside the 5-minute window; it can
only repeat a transition that is already applied (no-op) or one that is legal anyway.

## Consequences
- `release` is best-effort; if it fails, the retry is treated as a replay until the TTL (5 min) and the
  reconciler (ADR-0006) is the backstop.
- Callback order guarantees: duplicates and stale → safe; early → deferred via 409; unknown reference → 200
  (reconciler recovers).
