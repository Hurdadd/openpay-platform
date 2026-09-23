# ADR-0010: Append-only enforced in the database, not just in code

## Status
Accepted (PAY-6)

## Context
AC4 requires append-only tables: no `UPDATE`/`DELETE`, corrections only via reversing entries.
Application code alone cannot guarantee this — bugs, admin consoles, and ad-hoc SQL all bypass
it. The invariant must hold regardless of the caller.

## Decision
- Three `BEFORE UPDATE OR DELETE ... FOR EACH ROW` triggers (V2) raise on `account`,
  `journal_entry`, and `posting`. Any mutation attempt fails loudly at the database.
- Balance is enforced by a separate `AFTER INSERT ... DEFERRABLE INITIALLY DEFERRED` constraint
  trigger on `posting` (V1): it fires at commit time, so a transaction inserting a full balanced
  set passes, while any commit leaving an unbalanced entry fails.
- Domain validation (`JournalEntry`: ≥2 postings, sum == 0) stays as the first, fast layer.
  The database is the second layer, not a replacement: it also covers the case the domain
  cannot — an entry inserted with zero postings never fires the per-row balance trigger.

## Consequences
- Tests assert both layers: domain unit tests for the fail-fast path, raw-SQL tests for the
  trigger paths (unbalanced insert rejected, `UPDATE`/`DELETE` rejected).
- `TRUNCATE` is intentionally *not* blocked (no truncate trigger) — tests use it for isolation
  between cases. Production roles should additionally `REVOKE` truncate-adjacent privileges;
  logged as ops debt.
- Migrations only move forward: V1 was never applied anywhere before V2 was added, which is
  the only reason V1 itself was still editable during development.
