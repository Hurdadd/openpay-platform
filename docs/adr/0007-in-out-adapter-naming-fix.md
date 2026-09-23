# ADR-0007: Fixed inverted `in`/`out` adapter package naming

## Status
Accepted (PAY-4 follow-up)

## Context
The adapter module had `PaymentController` (a REST controller — something the outside world calls
*into* our system) under `adapter.out.web.payment`, and the JPA persistence adapters (`JpaPaymentEntityImpl`,
`JpaIdempotencyStore`, and friends — things *our* system calls *out* to a database) under
`adapter.in.persistence.*`. This is backwards relative to the standard hexagonal-architecture naming.

Per Alistair Cockburn (who coined the pattern), adapters split into two kinds by which direction
initiates the call:
> "Primary" or "driving" adapters are the ones that initiate action against the application — they
> call in. "Secondary" or "driven" adapters are the ones that the application initiates action
> against — the application calls out to them.
(https://alistair.cockburn.us/hexagonal-architecture/)

A REST controller is called *by* an external client (merchant, PSP) — it drives the application, so it
is a **primary/driving** adapter → belongs under `in`. A JPA repository implementation is called *by*
our own application code reaching *out* to the database — it is a **secondary/driven** adapter →
belongs under `out`. "Get Your Hands Dirty on Clean Architecture" (the book PAY-2 onward has been
following) uses this exact convention: `adapter.in.web`, `adapter.out.persistence`.

The project had it exactly inverted. This was flagged directly by the user, who caught it by inspecting
the package tree and cross-checking both the original Cockburn article and the book the project already
follows.

## Decision
Renamed packages to match the correct convention:
- `adapter.in.persistence.*` → `adapter.out.persistence.*` (`payment/`, `idempotency/`, and their
  `mapper` subpackages)
- `adapter.out.web.payment` → `adapter.in.web.payment` (`PaymentController` and its `dto` subpackage)

`PspCallbackController` (added in PAY-4) is left under `adapter.psp.callback` — organized by feature
slice rather than by the `in`/`out` split, consistent with how the rest of the PSP integration
(`adapter.psp.*`) and security (`adapter.security.*`) code in this module is already organized outside
the `web`/`persistence` packages. It is still a driving/inbound adapter by role; the `in`/`out` split
here is a *naming convention for two specific package trees* (`web`, `persistence`), not a rule
enforced uniformly across every adapter class in the module.

## Consequences
- Package names now match their architectural role, which is the entire point of naming them `in`/`out`
  in the first place — a reviewer (or an interviewer looking at this portfolio project) can trust the
  package name to tell them which direction the dependency/call actually flows, without having to open
  the file.
- Moved via `git mv` per file (not per directory — a bulk directory rename hit a filesystem permission
  error on this environment) to preserve file history; all package declarations and imports across the
  module were updated in the same change, verified by a full recompile + test run of every module.
- No behavior changed — this was a pure rename, not a redesign. `docs/flow.md`,
  `docs/classes/adapter.md`, and `docs/classes/testing.md` were updated to reference the corrected
  paths.
