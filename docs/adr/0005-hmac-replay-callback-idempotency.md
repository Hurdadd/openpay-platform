# ADR-0005: HMAC verification, replay protection, and idempotency are three separate concerns

## Status
Accepted (PAY-4)

## Context
PSP webhooks are inherently:
- **Unauthenticated by default** — anyone who finds the URL can POST to it unless we verify it really
  came from the PSP.
- **At-least-once** — the PSP will retry a callback it didn't get a clean 2xx for, so the same logical
  event can arrive 2, 3, N times.
- **Unordered** — retries, PSP-side queueing, and network reordering mean a stale status can arrive
  after a newer one.

These are three different failure modes and need three different defenses — conflating them (e.g.
"idempotency covers replay too") leaves gaps.

## Decision
Three independent layers, applied in this order in `PspCallbackController`:

1. **HMAC signature verification** (`HmacVerifier`, constant-time compare via `MessageDigest.isEqual`)
   — proves the request body came from a party that knows the shared secret, over the *exact raw bytes*
   received (verified before JSON parsing, so a tamper that happens to still parse doesn't sneak past).
   Failure → `401`, and nothing downstream ever sees the payload.

2. **Replay protection** (`ReplayGuard` / `RedisReplayGuard`) — a signed, well-formed message can still
   be the *same* message delivered twice. Redis `SETIFABSENT` on a `nonce`-derived key (5-minute TTL,
   matching the window in which a timestamp is still considered valid) makes "have I seen this nonce
   before" atomic — no read-then-write race between concurrent callback deliveries. A timestamp outside
   the window is also treated as "replay" (rejected as a no-op), which additionally bounds how long a
   captured-and-replayed request stays exploitable even if the nonce store were somehow bypassed.

3. **Idempotent handling** (`PspCallbackService.handle`) — even without a replay attack, the *business
   effect* of processing the same verified, non-replayed status twice must be a no-op. This is enforced
   by checking the transition is still legal (`PaymentStatus.canTransitionTo`) before applying it: if the
   payment is already at the target status, or has moved past it, the callback is silently absorbed.

## Why not just layer 3?
Idempotency alone (checking current-vs-target status) doesn't stop an attacker replaying a captured,
validly-signed "AUTHORIZED" callback to probe timing or hammer the endpoint — the replay guard rejects
it before it ever reaches the payment lookup. And idempotency alone doesn't verify authenticity at all —
an unsigned or forged request would happily be "idempotent" too. All three layers are required; none
is redundant with another.

## Consequences
- An unknown `pspReference` (verified + non-replayed, but no matching payment) still returns `200 OK` —
  by the time signature and replay checks pass, we trust the PSP sent this; a payment record that
  doesn't exist yet (created after this callback, PAY-4's reconciler window covers exactly this gap) or
  that belongs to a different environment is not a client error worth retrying over. See
  [ADR-0006](0006-reconciler-polling-design.md) for the backstop this leans on.
- The controller reads the body as raw `byte[]` specifically so the exact bytes the PSP signed are what
  gets HMAC-verified — Spring's automatic `@RequestBody` deserialize-then-reserialize would not
  guarantee byte-for-byte fidelity with what was actually signed.
