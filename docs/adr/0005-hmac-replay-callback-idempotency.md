# ADR-0005: HMAC verification, replay protection, and idempotency are three separate concerns

## Status
Accepted (PAY-4), hardened after security review

## Context
PSP webhooks are inherently:
- **Unauthenticated by default** — anyone who finds the URL can POST to it unless we verify it really
  came from the PSP.
- **At-least-once** — the PSP will retry a callback it didn't get a clean 2xx for, so the same logical
  event can arrive 2, 3, N times.
- **Unordered** — retries, PSP-side queueing, and network reordering mean a stale status can arrive
  after a newer one.

Authentication, replay protection, and business idempotency are separate failure modes.

## Decision
Three independent layers are applied in this order in `PspCallbackController`:

1. **HMAC signature verification** (`HmacVerifier`, constant-time compare via
   `MessageDigest.isEqual`) authenticates one canonical byte sequence:

   ```text
   timestamp + "." + nonce + "." + rawBody
   ```

   The JSON body remains raw bytes until verification completes. Timestamp and nonce are part of the
   signed material as well: signing only the body would allow an attacker who captured a valid
   body/signature pair to supply a fresh timestamp and nonce and bypass the replay layer. Unknown PSP
   ids and malformed hex signatures are treated as verification failures rather than server errors.

2. **Replay protection** (`ReplayGuard` / `RedisReplayGuard`) uses Redis `SETIFABSENT` with a
   five-minute TTL. Keys are namespaced by PSP:

   ```text
   replay:<pspId>:nonce:<nonce>
   ```

   This avoids collisions when two independent PSPs happen to use the same nonce. A timestamp outside
   the five-minute acceptance window is rejected before Redis.

3. **Idempotent handling** (`PspCallbackService.handle`) checks the aggregate transition before
   applying it. A duplicate status or an out-of-order status that is no longer legal becomes a no-op.

## Why not just layer 3?
Business idempotency cannot prove authenticity and should not be used as a replay-control mechanism.
The HMAC protects the callback's authenticated data, the nonce/timestamp bounds replay, and the state
machine protects the payment from repeated/out-of-order business effects.

## Consequences
- Any PSP implementation that sends callbacks must compute its signature over the same canonical
  sequence: timestamp, dot, nonce, dot, then the exact body bytes.
- An unknown `pspReference` that is otherwise authenticated is acknowledged with `200 OK`; the
  reconciler is the recovery path for local payment state.
- The controller keeps `@RequestBody` as raw `byte[]` so verification covers exactly what arrived on
  the wire.
