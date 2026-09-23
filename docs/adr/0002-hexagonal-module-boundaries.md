# ADR-0002: Four-module hexagonal split (domain / application / adapter / bootstrap)

## Status
Accepted (PAY-2, revised PAY-4)

## Context
The project intentionally targets hexagonal/ports-and-adapters architecture as a learning goal, not
because the current complexity demands it. Early on, `domain` and `adapter` briefly shared a package
name (`com.github.hurdadd.payment`), which let adapter code reach into domain package-private members —
a boundary leak that defeated the point of separate modules.

## Decision
Four Gradle `java-library` modules, each its own package root:
- `domain` — no dependency on anything else in this project. Pure Java (records, plain classes,
  enums). No Spring, no JPA, no Jackson annotations.
- `application` — depends on `domain` via `api` (domain types like `Payment`/`PaymentId` appear in
  port method signatures, so consumers of `application` need them transitively). Contains inbound
  ports (`CreatePaymentUseCase`, `PspCallbackHandler`), outbound ports (`PaymentRepository`,
  `PspClient`, `IdempotencyStore`, `ReplayGuard`, `PspSecretResolver`), and services
  (`CreatePaymentService`, `PspCallbackService`, `PaymentReconciler`). Zero Spring dependency —
  verified by the module simply not having Spring on its classpath.
- `adapter` — depends on `application` via `api` (bootstrap needs to see application's ports through
  adapter to wire beans — see the PAY-4 fix below). Contains JPA entities/repositories, the HMAC
  verifier, Redis-backed replay guard, PSP client implementations, and web controllers.
- `bootstrap` — depends on `adapter`. The only module with `@SpringBootApplication` and any
  `@Configuration` classes. Wires every service constructor by hand in `@Bean` methods.

## PAY-4 correction
`adapter`'s dependency on `application` was originally `implementation`, not `api`. That is invisible
inside `adapter` itself (it compiles fine), but `bootstrap`'s `@Configuration` classes need
`application`'s ports (`PaymentRepository`, `PspCallbackHandler`, etc.) directly, and Gradle's
`implementation` scope does not leak those types to `bootstrap`'s compile classpath. This was a latent
bug — `UseCaseConfig` (from PAY-2) only "worked" because a different module ordering happened to compile
first in some task graphs; building `bootstrap` standalone failed with "package ... does not exist" for
every `application` port. Fixed by changing `adapter/build.gradle.kts` to `api(project(":...application"))`.

## Consequences
- Compile-enforced boundary: `domain` cannot accidentally import Spring; `application` cannot
  accidentally import JPA; violating the direction is a build failure, not a code-review nit.
- `api` vs `implementation` is not just a performance knob here — it is the difference between
  "this type is part of what I expose to consumers" (bootstrap needs application's ports) and
  "this is my private implementation detail." Getting it wrong produces confusing, module-order-dependent
  compile failures rather than a clean error at the boundary that's actually wrong.
