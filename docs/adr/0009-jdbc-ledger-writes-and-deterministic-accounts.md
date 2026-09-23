# ADR-0009: JdbcTemplate writes and deterministic accounts for the ledger

## Status
Accepted (PAY-6)

## Context
The ledger only ever inserts rows — entry header plus postings — and deduplicates with
`ON CONFLICT DO NOTHING`. JPA's value proposition (identity map, dirty checking, managed
lifecycle) is pure liability for an append-only store: there is nothing to track, and a managed
entity that gets accidentally mutated is precisely the failure mode the ledger forbids.
Idempotency additionally needs native SQL, which JPA cannot express.

Account rows must exist before postings reference them (`posting.account_id` FK), including
per-merchant payable accounts for merchants seen for the first time — possibly from two
instances concurrently.

## Decision
- `JdbcLedgerRepository` (behind the `LedgerRepository` port) uses `JdbcTemplate`.
  `update()` returns the affected-row count, which *is* the idempotency signal: `1` inserted,
  `0` duplicate → return early without touching postings. `save` returns `void` because a
  duplicate delivery is normal traffic, not an error.
- Row UUIDs are minted in the repository (`UUID.randomUUID()`); the domain carries no ids and
  stays persistence-ignorant. `created_at` is left to the database default — the DB clock is the
  audit truth, not the app clock.
- No `@Transactional` on the repository. The boundary is `RecordLedgerEntryService.record()`;
  the template joins that transaction. Tests replicate production with `TransactionTemplate` —
  without it, autocommit trips the deferred balance trigger after the first posting (proven by a
  failing test during development, which is exactly the trigger doing its job).
- Account ids are deterministic: `UUID.nameUUIDFromBytes("account:" + type + ":" + name + ":" +
  currency)` (UTF-8), upserted with `INSERT ... ON CONFLICT (name, currency) DO NOTHING`
  (unique constraint from V2). Two concurrent first-events compute the same id; one insert wins,
  the other conflicts; both proceed identically. No SELECT, so no read-then-write race.

## Consequences
- No entities, no `merge()`, no dirty-checking surprises; SQL is explicit and reviewable.
- Account provisioning needs no seed data and no separate lookup round-trip.
- Display names for accounts live in the `account` row (`name`, `type`); the deterministic id
  scheme must stay stable forever — changing the format orphans existing rows (documented here
  so a future change is a deliberate migration, not an accident).
