# ADR-0001: Centralized state machine on the `Payment` aggregate

## Status
Accepted (PAY-2)

## Context
A payment moves through `CREATED → PENDING_PSP → AUTHORIZED → SETTLED/REVERSED`, with `FAILED`
reachable from `CREATED` or `PENDING_PSP`. Multiple code paths can attempt to move a payment forward:
the create-payment flow, the PSP callback handler (PAY-4), and the reconciler (PAY-4). If each path
independently decided "is this transition legal," the rules would drift and duplicate.

## Decision
`PaymentStatus` owns a static `EnumSet`-based transition table (`canTransitionTo`). `Payment` exposes
only named intent methods (`authorize()`, `fail()`, `settle()`, `reverse()`, `markPending(PspReference)`)
that funnel through a single private `transitionTo(target)` guard. No caller can set `paymentStatus`
directly or bypass the guard.

## Consequences
- Every caller (callback service, reconciler, future ledger/notification consumers) gets the same
  guarantee for free: illegal transitions throw `IllegalStateException` instead of silently corrupting
  state.
- Adding a new terminal or intermediate status only requires touching `PaymentStatus`'s static
  initializer — the enforcement point never changes.
- The tradeoff: `Payment` cannot represent "in-flight, don't know yet" more granularly than
  `PENDING_PSP` — see [ADR-0006](0006-reconciler-polling-design.md) for how PAY-4 deals with that during
  reconciliation without adding new domain states.
