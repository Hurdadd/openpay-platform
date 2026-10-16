# ADR-0012: Two services (orchestrator + ledger), not a modular monolith

## Status
Accepted (PAY-206)

## Context
The system has two responsibilities with different rules: the **orchestrator** owns the payment lifecycle
(talks to PSPs, retries, callbacks, state machine) and the **ledger** owns money movement (append-only,
DB-enforced double entry). A modular monolith (one deployable, two modules, one database) was the obvious
alternative.

## Decision
Two deployable services, each with its own database (`openpay`, `openpay_ledger`), connected only by the
`PaymentSettled.v1` event over Kafka via the transactional outbox (ADR-0011). In `docker-compose.yml` they
reach infrastructure by service name (`postgres:5432`, `kafka:29092`, `redis`).

Why not the modular monolith:
- **The boundary is enforced, not conventional.** In a monolith nothing stops a "quick" join from the ledger
  into `payments`. With separate databases the only contract is the event, versioned and enveloped (ADR-0008).
- **Different failure and security profiles.** The orchestrator is exposed to PSPs and merchants and is
  full of timeouts and retries; the ledger must stay boring and correct. A PSP outage or a restart storm in
  the orchestrator cannot touch ledger writes, and the ledger can be down while payments keep settling
  (events wait in the outbox/Kafka).
- **Independent scaling.** The outbox publisher scales with `SKIP LOCKED`, the ledger consumer with
  partitions (3, keyed by `paymentId`); neither forces the other to scale.
- **It makes the failure semantics visible**: at-least-once delivery, redelivery, idempotent consumer. These
  are the project's point; a monolith would hide them behind a method call.

## Cost of the decision (what we pay)
- **Eventual consistency.** A settled payment appears in the ledger seconds later (outbox poll + Kafka), not
  in the same transaction. Anything reading both must tolerate the gap.
- **At-least-once means duplicates.** The ledger needs an idempotency key on every entry
  (`journal_entry.idempotency_key UNIQUE`) and the outbox needs a publisher.
- **Operational weight.** Two images, two databases, Kafka and Redis to run; local development needs
  `docker compose`; end-to-end tests are slower and need failure injection.
- **No cross-service transactions.** A ledger failure cannot roll back a settlement; it needs retries and
  a dead-letter path instead.
- **Contract management.** Changing `PaymentSettled` requires versioning (`v1`, `v2`) rather than a refactor.

If the team stayed this small and traffic stayed low, a modular monolith would be cheaper. The split is a
deliberate trade of simplicity for enforced boundaries and isolated failure.

## Consequences
- `scripts/demo.sh` shows the whole path: create payment, signed callback, ledger balance.
- A single multi-stage `Dockerfile` (build arg `MODULE`) builds either service.
