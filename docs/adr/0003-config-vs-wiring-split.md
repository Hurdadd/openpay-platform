# ADR-0003: All Spring wiring lives in `bootstrap`, never inside service classes

## Status
Accepted (PAY-2, extended PAY-4)

## Context
`application` is a Spring-free module by design (see [ADR-0002](0002-hexagonal-module-boundaries.md)).
That only holds if nothing in `application` or `domain` ever imports a Spring annotation. But someone
still has to construct `CreatePaymentService`, `PspCallbackService`, `PaymentReconciler`, `HmacVerifier`,
etc. and hand them their dependencies.

## Decision
Every application service and every adapter class that isn't already a Spring stereotype
(`@Component`/`@Repository`) is constructed explicitly in a `bootstrap`-module `@Configuration` class,
via a `@Bean` factory method that calls the plain constructor:

```java
@Bean
PspCallbackHandler pspCallbackHandler(PaymentRepository paymentRepository) {
    return new PspCallbackService(paymentRepository);
}
```

`bootstrap` config classes added in PAY-4: `CallbackConfig` (wires `PspCallbackService` and
`HmacVerifier`), `ReconcilerConfig` (wires `PaymentReconciler` plus its `@Scheduled` trigger).
Adapter classes that are inherently framework-integrated (JPA repositories via `@Repository`,
`RedisReplayGuard` via `@Component` because it directly depends on Spring's `StringRedisTemplate`)
are the exception — they're allowed `@Component` because they're already adapter-layer, framework-facing
code, not application-layer.

## Consequences
- `application` and `domain` stay testable with plain JUnit + hand-written fakes, no Spring context
  needed (see `PspCallbackServiceTest`, `PaymentReconcilerTest` — no `@SpringBootTest` anywhere in
  `application`'s test sources).
- Every wiring decision is visible in one place (`bootstrap/src/main/java/...`) instead of scattered
  across `@Autowired` fields or `@Service` annotations buried in service classes.
- Cost: one extra file per non-trivial dependency graph, and forgetting to add a `@Bean` produces a
  runtime `NoSuchBeanDefinitionException` rather than a compile error — this bit us once already (see
  [ADR-0002](0002-hexagonal-module-boundaries.md)'s `api` vs `implementation` issue, which was a
  visibility problem one layer down from this one).
