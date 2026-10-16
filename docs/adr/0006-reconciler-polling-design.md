# ADR-0006: Reconciler polling design — rate, backoff, age cap

## Status
Accepted (PAY-4), amended by PAY-205

## Context
PAY-4's golden-path scenario: the PSP actually authorized a payment, but its callback never reached us
(dropped, PSP-side bug, network partition). Without a backstop, that payment sits in `PENDING_PSP`
forever even though the money moved. The reconciler is a scheduled job that finds such payments and
asks the PSP directly.

## Decision

### Amendment (PAY-205): settle stale AUTHORIZED payments, isolate failures, age by `status_changed_at`
- **Candidates now include `AUTHORIZED`.** A payment whose SETTLED callback never arrived is polled too.
  `PspOutcome` has no SETTLED value, so `APPROVED` for a payment that is already `AUTHORIZED` is read as
  "captured" and the reconciler calls `Payment.settle()`. `settle()` adds the `PaymentSettled` event and
  `PaymentRepository.save()` writes the outbox row in the same transaction (ADR-0011), so the ledger
  eventually gets the money. `DECLINED` for an `AUTHORIZED` payment is ignored (no `AUTHORIZED → FAILED`
  transition). A `PENDING_PSP` payment still moves one step per run (→ `AUTHORIZED`); it is settled by a
  later run once it has been `AUTHORIZED` for `stuckThreshold`.
- **Per-payment isolation.** Each payment has its own transaction (`tx.run` around the re-read + write) and
  its own `try/catch (RuntimeException)` in the loop. An `OptimisticLockException` on one row (a callback
  won the race) is logged and skipped; the rest of the batch continues and the row is retried next run.
- **`status_changed_at` replaces `created_at` for selection.** Migration `V5` adds the column
  (backfilled from `created_at`, `NOT NULL`, indexed with `status`). `Payment.transitionTo` sets it on every
  transition. `stuckThreshold` and `maxAge` are now measured from the last status change: an old payment that
  was authorized a minute ago is not "stuck", and one authorized 3 hours ago is.

### Candidate selection
`PaymentReconciler.reconcile()` queries `PaymentRepository.findStuckPending(olderThan, newerThan)`:
- (Age is `status_changed_at`, see the amendment above.)
- `olderThan = now - stuckThreshold` (default **2 minutes**) — don't poll a payment that might still get
  its callback any second; give the normal callback path a fair head start.
- `newerThan = now - maxAge` (default **24 hours**) — payments older than this are excluded from
  automatic reconciliation. A payment still `PENDING_PSP` after 24h is treated as a data/ops problem
  (manual investigation), not something to keep silently re-polling forever.

### Per-candidate handling
- No `pspReference` yet → skip. Nothing to ask the PSP about (the payment never got far enough to be
  assigned one).
- `PspClient.checkStatus()` throws (PSP unreachable, or `ResilientPspClient`'s retry/circuit-breaker
  chain already exhausted its attempts) → leave `PENDING_PSP`, try again next run. **"Didn't get an
  answer" is not "got a FAILED answer"** — treating a timeout/error as failure risks incorrectly failing
  a payment the PSP actually authorized.
- PSP reports `APPROVED` → `Payment.authorize()`.
- PSP reports `DECLINED` → `Payment.fail()`.
- PSP reports `TIMEOUT` (the PSP's own "I don't know either" signal) → leave `PENDING_PSP`, try again
  next run.
- Every transition still goes through `Payment.transitionTo`'s guard (see
  [ADR-0001](0001-payment-state-machine.md)) — if the payment moved on through some other path between
  candidate selection and this check (e.g. the callback arrived a second earlier), the now-illegal
  transition is silently skipped rather than throwing mid-batch.

### Poll interval and backoff
`reconciler.poll-interval` (default **30 seconds**) is a fixed delay between scheduled runs
(`@Scheduled(fixedDelayString = "${reconciler.poll-interval}")` in `ReconcilerConfig`). Per-candidate
backoff is intentionally *not* implemented as custom logic in the reconciler — it's delegated entirely
to `ResilientPspClient`'s existing Retry/CircuitBreaker chain (see
[ADR-0004](0004-resilience4j-programmatic-decorator.md)): if the PSP is flapping, the circuit breaker
opens and subsequent `checkStatus()` calls fail fast without hammering it, self-limiting the effective
poll rate against a struggling PSP without a second backoff mechanism to keep in sync with the first.

## Maximum detection window (the number the ticket asks for)

Worst case: a payment crosses the `stuckThreshold` age just *after* a scheduled run has already
started/finished, so it has to wait almost one full extra `poll-interval` before the next run picks it
up.

```
max_detection_time = stuckThreshold + poll-interval
                    = 2 minutes + 30 seconds
                    = 150 seconds
```

This is the number covered by `PaymentReconcilerTest.payment_authorized_at_psp_but_callback_never_arrived_is_reconciled_to_authorized`
(and its sibling `payment_not_yet_past_stuck_threshold_is_left_alone`, which pins the lower bound of the
window). If a PSP outage causes `checkStatus()` to keep failing, the effective detection time is longer
and bounded instead by the circuit breaker's `waitDurationInOpenState` (10s) cycling plus however many
runs it takes the breaker to close again — not accounted for in the 150s figure above, which assumes a
reachable PSP.

## Consequences
- `stuckThreshold`, `poll-interval`, and `maxAge` are all externalized to `application.yml`
  (`reconciler.*`), so the 150s figure is a documented, tunable property of the deployed config, not a
  hardcoded constant buried in code.
- The reconciler shares `PspClient` (and therefore the circuit breaker) with the live authorize path —
  a deliberate choice, not an oversight: reconciliation traffic against a struggling PSP should be
  throttled by the same signal as live traffic, not run on an isolated budget that could keep hammering
  a PSP the live path has already backed off from.
