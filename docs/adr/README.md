# Architecture Decision Records

| ADR | Title | Ticket |
|---|---|---|
| [0001](0001-payment-state-machine.md) | Centralized state machine on the `Payment` aggregate | PAY-2 |
| [0002](0002-hexagonal-module-boundaries.md) | Four-module hexagonal split (domain/application/adapter/bootstrap) | PAY-2, PAY-4 |
| [0003](0003-config-vs-wiring-split.md) | All Spring wiring lives in `bootstrap` | PAY-2, PAY-4 |
| [0004](0004-resilience4j-programmatic-decorator.md) | Programmatic Resilience4j decorator, not annotations | PAY-3, PAY-4 |
| [0005](0005-hmac-replay-callback-idempotency.md) | HMAC verification, replay protection, and idempotency are three separate concerns | PAY-4 |
| [0006](0006-reconciler-polling-design.md) | Reconciler polling design — rate, backoff, age cap | PAY-4 |
| [0007](0007-in-out-adapter-naming-fix.md) | Fixed inverted `in`/`out` adapter package naming | PAY-4 |
