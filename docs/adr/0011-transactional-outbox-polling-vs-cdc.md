# ADR-0011: Transactional outbox — write path, `@Transactional` pitfalls, polling vs CDC

## Status
Accepted (PAY-204)

## Context
`payment_outbox` existed and `OutboxPublisher` read it, but no production code wrote to it, so
"payment settled" and "event published" were still a dual write: a crash between the two loses the
event or announces a state that never committed.

## Decision
- **Domain collects, adapter persists.** `Payment.settle()` records a `PaymentSettled` event on the
  aggregate; `JpaPaymentEntityImpl.save()` drains it with `pullEvents()` and inserts the outbox row
  through the same `EntityManager`, i.e. the same transaction as the payment `UPDATE`. The domain
  knows nothing about Kafka or tables; only the adapter maps event -> `PaymentSettledV1Payload`
  (minor units, ADR-0008).
- **`save()` is `@Transactional(propagation = MANDATORY)`.** Spring Data's own `save` opens a
  transaction when none exists, which would silently commit the payment and outbox rows
  separately. MANDATORY turns "forgot to wrap" into a loud `IllegalTransactionStateException`.
- **Transactions are opened in the services, via `TxRunner`** (a `TransactionTemplate` bean in
  `bootstrap`, ADR-0003): `PspCallbackService.handle` wraps read+transition+save;
  `PaymentReconciler` calls the PSP outside any transaction and only the write is transactional,
  re-reading the row first so a callback that arrived meanwhile is not overwritten with stale state.
- **Ordering:** `OutboxPublisher` sends with key = `paymentId`, so all events of one payment hash
  (murmur2) to one partition. Per-payment order is preserved; there is no global order.

## `@Transactional` on a private method — tried, it fails silently
`TransactionalProxyPitfallTest` runs the experiment:

| Call | Transaction active? |
|---|---|
| public `@Transactional` method called from outside (through the proxy) | yes |
| private `@Transactional` method | **no** |
| public `@Transactional` method called via `this` from another method (self-invocation) | **no** |

Cause: `@Transactional` is applied by an AOP proxy that wraps the bean. The proxy only intercepts
calls that cross it (public methods, from another bean). A private method cannot be overridden or
reached by the proxy, and `this.foo()` is a plain JVM call on the target object that never enters
the proxy. Nothing fails at startup or at runtime; the code just runs without a transaction. This
is why the project wraps with an explicit `TransactionTemplate` (visible in the code, not dependent
on proxy mechanics) and uses MANDATORY as a guard where a missing transaction would be a bug.

## Polling outbox vs Debezium/CDC
Chosen now: **polling** (`SELECT ... FOR UPDATE SKIP LOCKED LIMIT 100`, every 5 s).
- Fits this scale: one table, one service, no extra infrastructure; `SKIP LOCKED` lets several
  publisher instances share the work without double-sending.
- Cost: up to ~5 s latency, constant polling load, and a publisher crash after the Kafka ack but
  before commit re-sends -> delivery is **at-least-once**; consumers dedupe on `eventId` (ADR-0008).
- CDC (Debezium reading the WAL) gives low latency and no polling, but adds Kafka Connect, a
  replication slot to monitor (an unconsumed slot grows WAL on the DB), and connector operations.
  Revisit when latency requirements drop below seconds or the poll query becomes visible in DB load.

## Consequences
- Settlement and its event are atomic; tests prove commit (one row) and rollback (no status change,
  no row, forced to the DB with a flush before the exception).
- Only `PaymentSettled` is emitted. `EventTypes.PAYMENT_FAILED_V1` exists but nothing produces it
  and the outbox entity's payload type is settlement-specific; a second event type needs a payload
  abstraction first.
- The partition test checks key equality + the murmur2 formula, not a real broker (no embedded
  Kafka dependency yet).
