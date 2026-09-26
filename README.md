# OpenPSP — Payment Orchestrator

An open-source payment orchestrator built to answer one question under failure:

> **When the PSP times out, a callback is lost, or Kafka redelivers, does money get lost or counted twice?**

The design goal is that it doesn't. A payment flows from an idempotent REST API, through an explicit state machine and a resilient PSP adapter, into a transactional outbox, over Kafka, and into a double-entry ledger whose invariants are enforced by the database itself.

![Java 21](https://img.shields.io/badge/Java-21-blue) ![Spring Boot 4](https://img.shields.io/badge/Spring%20Boot-4.0-green) ![PostgreSQL 17](https://img.shields.io/badge/PostgreSQL-17-blue) ![Kafka KRaft](https://img.shields.io/badge/Kafka-3.9%20KRaft-black)

---

## Table of contents

1. [Status — what works today](#status--what-works-today)
2. [Architecture](#architecture)
3. [The money path](#the-money-path)
4. [Payment state machine](#payment-state-machine)
5. [Domain model (UML)](#domain-model-uml)
6. [Data model](#data-model)
7. [Guarantees and how each one is proven](#guarantees-and-how-each-one-is-proven)
8. [Resilience and timeout budget](#resilience-and-timeout-budget)
9. [Getting started](#getting-started)
10. [Testing](#testing)
11. [Project layout](#project-layout)
12. [Architecture Decision Records](#architecture-decision-records)
13. [Known limitations](#known-limitations)

---

## Status — what works today

This project is in progress. The table is kept honest: a component is ✅ only when it is implemented **and** covered by a test that runs in CI.

| Component | Status | Notes |
|---|---|---|
| Payment aggregate + state machine | ✅ | Framework-free domain module; illegal transitions throw |
| Idempotent `POST /payments` (key + SHA-256 body hash, 409 on mismatch) | 🚧 | Store logic tested; HTTP binding of `merchantId` is being fixed |
| Optimistic locking (`@Version`) | ✅ | Concurrent-thread test |
| PSP adapter — retry, circuit breaker, time limiter | 🚧 | Implemented; not yet called from the create flow; backoff config being corrected |
| HMAC-verified PSP callbacks + Redis replay guard | ✅ / 🚧 | Verified & tested; ordering of replay-mark vs processing being fixed |
| Reconciler for payments stuck in `PENDING_PSP` | ✅ / 🚧 | Tested in isolation; becomes live once the PSP call is wired |
| Transactional outbox → Kafka publisher | 🚧 | Publisher done (`acks=all`, idempotent producer, `SKIP LOCKED`); writing the outbox row in the state-change transaction is in progress |
| Double-entry ledger (DB-enforced balance, append-only) | ✅ | Deferred constraint triggers; redelivery test with a crashed consumer |
| End-to-end failure-injection test | 🚧 | In progress |

---

## Architecture

### System context

```mermaid
flowchart LR
    Merchant([Merchant / Client])
    PSP([Payment Service Provider])

    subgraph OpenPSP
        direction LR
        PO["payment-orchestrator<br/>:8080"]
        LS["ledger-service<br/>:8081"]
    end

    PG1[("PostgreSQL<br/>db: openpay")]
    PG2[("PostgreSQL<br/>db: openpay_ledger")]
    RD[("Redis<br/>replay nonces")]
    K{{"Kafka (KRaft)<br/>topic: payment-events<br/>3 partitions"}}

    Merchant -- "POST /payments<br/>Idempotency-Key" --> PO
    PO -- "authorize / checkStatus<br/>(Resilience4j)" --> PSP
    PSP -- "POST /callbacks/psp/{pspId}<br/>HMAC signed" --> PO
    PO --- PG1
    PO --- RD
    PO -- "outbox publisher<br/>key = paymentId" --> K
    K -- "consumer group<br/>ledger-service" --> LS
    LS --- PG2
```

Two services, two databases, one event stream. The orchestrator owns the payment lifecycle; the ledger owns money movement. They share nothing but the `PaymentSettled.v1` event contract. See [ADR index](docs/adr/README.md) for why the boundary sits there.

### Hexagonal module structure

Each service is split into four Gradle modules. Dependencies point inward only; the domain has zero framework dependencies (verifiable with `./gradlew :payment-orchestrator:domain:dependencies`).

```mermaid
flowchart TB
    subgraph payment-orchestrator
        direction TB
        B[bootstrap<br/><i>Spring wiring, @Configuration, main</i>]
        A[adapter<br/><i>REST, JPA, Kafka, Redis, PSP clients</i>]
        AP[application<br/><i>use cases, ports in/out</i>]
        D[domain<br/><i>Payment, Money, PaymentStatus — plain Java</i>]
        B --> A
        B --> AP
        A --> AP
        AP --> D
        A --> D
    end
```

```mermaid
flowchart LR
    subgraph "Inbound adapters"
        C1[PaymentController]
        C2[PspCallbackController]
        C3[ReconcilerScheduler]
    end
    subgraph "Application (ports)"
        U1[[CreatePaymentUseCase]]
        U2[[PspCallbackHandler]]
        U3[PaymentReconciler]
        P1[[PaymentRepository]]
        P2[[IdempotencyStore]]
        P3[[PspClient]]
        P4[[ReplayGuard]]
    end
    subgraph "Outbound adapters"
        O1[JpaPaymentEntityImpl]
        O2[JpaIdempotencyStore]
        O3[ResilientPspClient → SlowPsp / FlakyPsp]
        O4[RedisReplayGuard]
        O5[OutboxPublisher]
    end
    C1 --> U1
    C2 --> P4
    C2 --> U2
    C3 --> U3
    U1 --> P1 & P2
    U2 --> P1
    U3 --> P1 & P3
    P1 -.implemented by.-> O1
    P2 -.implemented by.-> O2
    P3 -.implemented by.-> O3
    P4 -.implemented by.-> O4
```

---

## The money path

### Happy path (target flow)

```mermaid
sequenceDiagram
    autonumber
    actor M as Merchant
    participant API as PaymentController
    participant UC as CreatePaymentService
    participant DB as Postgres (openpay)
    participant PSP as PSP
    participant CB as PspCallbackController
    participant OB as OutboxPublisher
    participant K as Kafka
    participant L as Ledger consumer
    participant LDB as Postgres (openpay_ledger)

    M->>API: POST /payments (Idempotency-Key: K)
    API->>UC: CreatePaymentCommand
    UC->>DB: BEGIN · INSERT idempotency_keys ON CONFLICT DO NOTHING
    UC->>DB: INSERT payments (CREATED) · store response · COMMIT
    UC->>PSP: authorize(amount, pspReference)  [outside DB tx]
    PSP-->>UC: accepted
    UC->>DB: UPDATE payments → PENDING_PSP (version check)
    API-->>M: 201 {paymentId, status}

    PSP->>CB: callback AUTHORIZED (HMAC, ts, nonce)
    CB->>CB: verify HMAC over ts.nonce.body · replay window
    CB->>DB: UPDATE → AUTHORIZED
    PSP->>CB: callback SETTLED
    CB->>DB: BEGIN · UPDATE → SETTLED · INSERT payment_outbox · COMMIT

    loop every 5s
        OB->>DB: SELECT … FOR UPDATE SKIP LOCKED LIMIT 100
        OB->>K: send(key = paymentId, PaymentSettled.v1)  acks=all
        OB->>DB: mark published
    end

    K->>L: PaymentSettled.v1
    L->>LDB: BEGIN · INSERT journal_entry ON CONFLICT (idempotency_key) DO NOTHING
    L->>LDB: INSERT postings (+clearing, −merchant) · deferred balance check · COMMIT
    L->>K: commit offset (after DB commit)
```

### Failure path — PSP times out, callback never arrives

"No answer" is treated as **unknown**, never as **failed**. The reconciler resolves it by asking the PSP.

```mermaid
sequenceDiagram
    autonumber
    participant UC as CreatePaymentService
    participant PSP as PSP
    participant DB as Postgres
    participant R as PaymentReconciler

    UC->>PSP: authorize (single attempt, idempotency key = pspReference)
    PSP--xUC: timeout
    UC->>DB: status = PENDING_PSP (outcome unknown)
    Note over PSP: PSP actually approved,<br/>but its callback is lost
    loop every 30s
        R->>DB: find PENDING_PSP older than 2m and newer than 24h
        R->>PSP: checkStatus(pspReference)
        alt APPROVED
            R->>DB: → AUTHORIZED
        else DECLINED
            R->>DB: → FAILED
        else no answer / TIMEOUT
            R->>R: leave PENDING_PSP, retry next run
        end
    end
```

Worst-case detection window: `stuck-threshold + poll-interval = 2m + 30s = 150s` ([ADR-0006](docs/adr/0006-reconciler-polling-design.md)).

### Failure path — Kafka redelivery

```mermaid
sequenceDiagram
    participant K as Kafka
    participant L as Ledger consumer
    participant LDB as Ledger DB

    K->>L: PaymentSettled.v1 (eventId = E)
    L->>LDB: INSERT journal_entry (idempotency_key = E) + postings · COMMIT
    L--xK: crash before offset commit
    K->>L: redeliver same message (eventId = E)
    L->>LDB: INSERT … ON CONFLICT (idempotency_key) DO NOTHING → 0 rows
    L->>K: commit offset
    Note over LDB: balance unchanged — at-least-once delivery<br/>+ idempotent consumer = effectively-once
```

---

## Payment state machine

```mermaid
stateDiagram-v2
    [*] --> CREATED
    CREATED --> PENDING_PSP: PSP accepted / outcome unknown
    CREATED --> FAILED: rejected before PSP
    PENDING_PSP --> AUTHORIZED: callback or reconciler (APPROVED)
    PENDING_PSP --> FAILED: callback or reconciler (DECLINED)
    AUTHORIZED --> SETTLED: settlement callback → outbox event
    AUTHORIZED --> REVERSED: reversal
    FAILED --> [*]
    SETTLED --> [*]
    REVERSED --> [*]
```

Transitions live in one place (`PaymentStatus`) and are the only way to change state (`Payment.authorize()`, `settle()`, …). There are no setters. A **stale** callback (e.g. `AUTHORIZED` arriving after `SETTLED`) is a no-op. See [ADR-0001](docs/adr/0001-payment-state-machine.md).

---

## Domain model (UML)

```mermaid
classDiagram
    direction LR
    class Payment {
        -PaymentId paymentId
        -MerchantId merchantId
        -Money money
        -PaymentStatus paymentStatus
        -PspReference pspReference
        -Instant createdAt
        -long version
        +create(Money, MerchantId)$ Payment
        +reconstitute(...)$ Payment
        +markPending(PspReference)
        +authorize()
        +fail()
        +settle()
        +reverse()
    }
    class Money {
        -BigDecimal amount
        -Currency currency
        +of(BigDecimal, Currency)$ Money
    }
    class Currency {
        <<enumeration>>
        IRR(0)
        USD(2)
        EUR(2)
        AUD(2)
        +getMinorUnits() int
    }
    class PaymentStatus {
        <<enumeration>>
        CREATED
        PENDING_PSP
        AUTHORIZED
        FAILED
        SETTLED
        REVERSED
        +canTransitionTo(PaymentStatus) boolean
    }
    class PaymentId { <<value object>> -UUID value }
    class MerchantId { <<value object>> -UUID value }
    class PspReference { <<value object>> -String value }

    Payment *-- Money
    Payment *-- PaymentId
    Payment *-- MerchantId
    Payment o-- PspReference
    Payment --> PaymentStatus
    Money --> Currency

    class PaymentRepository { <<port>> +save(Payment) +findById(PaymentId) +findByPspReference(PspReference) +findStuckPending(Instant, Instant) }
    class IdempotencyStore { <<port>> +tryInsert(key, hash) IdempotencyResult +saveResponse(key, StoredResponse) }
    class PspClient { <<port>> +authorize(AuthorizationRequest) PspResult +checkStatus(PspReference) PspResult }
    class IdempotencyResult { <<sealed>> }
    class New
    class Duplicate
    class Conflict
    IdempotencyResult <|-- New
    IdempotencyResult <|-- Duplicate
    IdempotencyResult <|-- Conflict
```

Ledger domain:

```mermaid
classDiagram
    class JournalEntry {
        <<record>>
        +List~Posting~ postings
        +String description
        +Currency currency
        invariant: postings ≥ 2
        invariant: Σ amount = 0
    }
    class Posting {
        <<record>>
        +long amount  // minor units, + debit / − credit
        +String accountId
    }
    JournalEntry "1" *-- "2..*" Posting
```

---

## Data model

### Orchestrator (`openpay`)

```mermaid
erDiagram
    payments {
        uuid payment_id PK
        uuid merchant_id
        numeric amount "NUMERIC(19,4)"
        varchar currency
        varchar status
        varchar psp_reference UK "partial unique, nullable"
        timestamptz created_at
        bigint version "optimistic lock"
    }
    idempotency_keys {
        varchar idempotency_key PK
        char body_hash "SHA-256 of canonical body"
        jsonb response "replayed on duplicate"
        timestamptz created_at
    }
    payment_outbox {
        uuid id PK "becomes eventId"
        uuid payment_id "Kafka partition key"
        varchar event_type
        jsonb payload
        boolean published "partial index WHERE NOT published"
        timestamptz created_at
        timestamptz published_at
    }
    payments ||--o{ payment_outbox : "emits"
```

### Ledger (`openpay_ledger`)

```mermaid
erDiagram
    account {
        uuid id PK "deterministic: UUIDv3(type:name:currency)"
        varchar name
        varchar type "CLEARING | MERCHANT_PAYABLE"
        varchar currency
    }
    journal_entry {
        uuid id PK
        varchar idempotency_key UK "= eventId"
        varchar description
        varchar currency
        timestamptz created_at
    }
    posting {
        uuid id PK
        uuid journal_entry_id FK
        uuid account_id FK
        bigint amount "minor units, signed"
        timestamptz created_at
    }
    journal_entry ||--|{ posting : "2..*"
    account ||--o{ posting : ""
```

Enforced **in PostgreSQL**, not only in Java:

| Invariant | Mechanism |
|---|---|
| Every entry has ≥ 2 postings and sums to 0 | `DEFERRABLE INITIALLY DEFERRED` constraint triggers on `posting` and `journal_entry`, checked at commit |
| Ledger is append-only | `BEFORE UPDATE OR DELETE` triggers reject any mutation of `account`, `journal_entry`, `posting` |
| One entry per event | `UNIQUE (idempotency_key)` + `ON CONFLICT DO NOTHING` |
| No floating point money | `BIGINT` minor units on the wire and in the ledger |

### Settlement posting

For `PaymentSettled.v1` of 10,000 minor units:

| Account | Type | Amount |
|---|---|---|
| `psp_clearing` | CLEARING | **+10,000** (debit — money receivable from PSP) |
| `merchant:{merchantId}` | MERCHANT_PAYABLE | **−10,000** (credit — money owed to merchant) |
| | **Σ** | **0** |

### Event contract — `PaymentSettled.v1`

Schema lives in [`docs/events/PaymentSettled.v1.json`](docs/events/PaymentSettled.v1.json). The version is part of `eventType`; consumers reject unknown types.

```json
{
  "eventId": "33333333-3333-3333-3333-333333333333",
  "eventType": "PaymentSettled.v1",
  "data": {
    "paymentId": "11111111-1111-1111-1111-111111111111",
    "merchantId": "22222222-2222-2222-2222-222222222222",
    "amountMinor": 10000,
    "currency": "IRR",
    "settledAt": "2026-09-21T10:00:00Z"
  }
}
```

---

## Guarantees and how each one is proven

| Failure | Mechanism | Proof |
|---|---|---|
| Client retries `POST /payments` | Idempotency key + body hash; stored response replayed | `CreatePaymentService` + `JpaIdempotencyStore` |
| Same key, different body | `409 Conflict` | idempotency store tests |
| Two writers update the same payment | `@Version` optimistic lock — exactly one wins | `PaymentEntityConcurrencyTest` |
| Illegal state change (e.g. `SETTLED → PENDING_PSP`) | Transition table in `PaymentStatus` | `PaymentTest` |
| Forged callback | HMAC-SHA256 over `timestamp.nonce.rawBody`, constant-time compare | `HmacVerifierTest` |
| Replayed callback | ±5 min window + Redis `SET NX` nonce | `RedisReplayGuardTest` |
| Same callback delivered 3× | State machine no-ops on already-applied status | `PspCallbackServiceTest` |
| Callback lost | Reconciler polls PSP for stuck `PENDING_PSP` | `PaymentReconcilerTest` |
| PSP down | Circuit breaker opens, fails fast | `ResilientPspClientTest` |
| Kafka redelivers a settled event | Unique `idempotency_key` in ledger | `LedgerKafkaRedeliveryTest` (consumer killed between DB commit and offset commit) |
| Buggy code writes an unbalanced entry | Deferred DB trigger rolls back the transaction | `LedgerRepositoryTest` |
| Crash between state change and publish | Transactional outbox | 🚧 in progress |
| All of the above, end to end | Testcontainers + PSP fault injection | 🚧 in progress |

Delivery semantics are **at-least-once everywhere, with idempotent receivers**. Nothing in this system claims exactly-once.

---

## Resilience and timeout budget

`ResilientPspClient` decorates every PSP call as `Retry( CircuitBreaker( TimeLimiter( call ) ) )`, so each retry attempt passes through the breaker and is individually time-limited ([ADR-0004](docs/adr/0004-resilience4j-programmatic-decorator.md)).

| Setting | Value |
|---|---|
| Time limit per attempt | 500 ms |
| Retry attempts | 5 (not retried on `PspClientException` — 4xx-class errors) |
| Circuit breaker | count-based window 10, opens at 50 % failures, 10 s open, 10 calls half-open |
| Reconciler | every 30 s; candidates older than 2 m, younger than 24 h |

**Timeout budget (end-to-end SLA 3 s)** — target values; the resilience config is being aligned with this table:

| Layer | Budget |
|---|---|
| Ingress + validation + idempotency insert | 150 ms |
| DB transaction (create) | 150 ms |
| PSP authorize — **single attempt**, no retry | 2,000 ms |
| DB update to `PENDING_PSP` | 150 ms |
| Headroom | 550 ms |

`authorize` must not be retried blindly: a timeout means *unknown*, and a retry without a PSP-side idempotency key can charge twice. `checkStatus` is a read, so retries are safe there.

---

## Getting started

### Prerequisites

- JDK 21
- Docker with Compose v2

### Run the infrastructure

```bash
docker compose up -d          # postgres:17, redis:7.4, kafka 3.9 (KRaft), topic bootstrap
docker compose ps
```

This creates two databases in one PostgreSQL instance (`openpay`, `openpay_ledger`) and the `payment-events` topic with 3 partitions. Flyway migrates each schema on service start-up.

### Run the services

```bash
./gradlew :payment-orchestrator:bootstrap:bootRun   # :8080
./gradlew :ledger-service:bootstrap:bootRun         # :8081
```

### Try it

> The requests below describe the target behaviour. Until the JSON binding of `merchantId` and the PSP call in the create flow are in place, the create request is rejected and callbacks find no matching payment.

Create a payment:

```bash
curl -i -X POST localhost:8080/payments \
  -H 'Content-Type: application/json' \
  -H 'Idempotency-Key: order-42' \
  -d '{"amount": 100.00, "currency": "USD", "merchantId": "22222222-2222-2222-2222-222222222222"}'
```

Send the same request again: you get the same `paymentId`, and no new row is created. Change the amount with the same key and you get `409 Conflict`.

Send a signed PSP callback:

```bash
SECRET=dev-secret-change-me
TS=$(date +%s)
NONCE=$(uuidgen)
BODY='{"pspReference":"<psp-ref>","status":"AUTHORIZED"}'
SIG=$(printf '%s.%s.%s' "$TS" "$NONCE" "$BODY" | openssl dgst -sha256 -hmac "$SECRET" -hex | awk '{print $2}')

curl -i -X POST localhost:8080/callbacks/psp/slow-psp \
  -H 'Content-Type: application/json' \
  -H "X-Psp-Timestamp: $TS" -H "X-Psp-Nonce: $NONCE" -H "X-Psp-Signature: $SIG" \
  -d "$BODY"
```

Inspect the ledger:

```bash
docker exec -it openpay-postgres psql -U openpay -d openpay_ledger -c \
  "SELECT a.name, SUM(p.amount) AS balance FROM posting p JOIN account a ON a.id = p.account_id GROUP BY a.name;"
```

### Configuration

| Variable | Default | Used by |
|---|---|---|
| `PAYMENT_DB_URL` | `jdbc:postgresql://localhost:5432/openpay` | orchestrator |
| `LEDGER_DB_URL` | `jdbc:postgresql://localhost:5432/openpay_ledger` | ledger |
| `KAFKA_BOOTSTRAP_SERVERS` | `localhost:9092` | both |
| `REDIS_HOST` / `REDIS_PORT` / `REDIS_PASSWORD` | `localhost` / `6379` / `openpay-redis` | orchestrator |
| `SLOW_PSP_SECRET` | `dev-secret-change-me` | callback HMAC |

---

## Testing

```bash
./gradlew build     # unit + integration tests (Testcontainers needs Docker)
```

| Level | What | Tooling |
|---|---|---|
| Domain | State machine, `Money`, `JournalEntry` invariants | JUnit 5, no Spring |
| Application | Callback idempotency, reconciler decisions | JUnit 5 with in-memory ports |
| Adapter | Optimistic locking, idempotency store, HMAC, replay guard, outbox publisher, circuit breaker | Testcontainers (PostgreSQL), Mockito |
| Ledger | DB triggers, redelivery after crash | Testcontainers (PostgreSQL, Kafka) |
| End-to-end | Full money path under PSP timeout + manual event re-injection | 🚧 in progress |

---

## Project layout

```
.
├── payment-orchestrator/
│   ├── domain/          Payment, Money, PaymentStatus — no framework
│   ├── application/     use cases, ports, reconciler, callback service
│   ├── adapter/         REST, JPA, Flyway (V1–V4), Kafka outbox, Redis, PSP clients
│   └── bootstrap/       Spring Boot main + wiring
├── ledger-service/
│   ├── domain/          JournalEntry, Posting
│   ├── application/
│   ├── adapter/         Kafka listener, JDBC ledger repository, Flyway (V1–V4)
│   └── bootstrap/
├── psp-gateway/         placeholder, to be replaced by a PSP stub in the end-to-end tests
├── docs/
│   ├── adr/             Architecture Decision Records
│   └── events/          event contracts
├── docker/postgres/init creates the ledger database
└── docker-compose.yml   postgres, redis, kafka (KRaft), topic init
```

---

## Architecture Decision Records

| ADR | Decision |
|---|---|
| [0001](docs/adr/0001-payment-state-machine.md) | Centralized state machine on the `Payment` aggregate |
| [0002](docs/adr/0002-hexagonal-module-boundaries.md) | Four-module hexagonal split |
| [0003](docs/adr/0003-config-vs-wiring-split.md) | All Spring wiring lives in `bootstrap` |
| [0004](docs/adr/0004-resilience4j-programmatic-decorator.md) | Programmatic Resilience4j decorator, not annotations |
| [0005](docs/adr/0005-hmac-replay-callback-idempotency.md) | HMAC, replay protection and idempotency are three separate concerns |
| [0006](docs/adr/0006-reconciler-polling-design.md) | Reconciler polling — rate, backoff, age cap |
| [0007](docs/adr/0007-in-out-adapter-naming-fix.md) | In/out adapter package naming |
| [0008](docs/adr/0008-wire-minor-units-and-event-envelope.md) | Minor units on the wire and the event envelope |
| [0009](docs/adr/0009-jdbc-ledger-writes-and-deterministic-accounts.md) | JDBC ledger writes and deterministic account ids |
| [0010](docs/adr/0010-append-only-db-enforcement.md) | Append-only ledger enforced in the database |

---

## Known limitations

Listed on purpose.

- The create flow does not call the PSP yet, so payments stay `CREATED` until it does.
- The outbox row is not yet written in the state-change transaction.
- An *early* callback (e.g. `SETTLED` while still `PENDING_PSP`) is acknowledged and dropped instead of being rejected for redelivery.
- The replay nonce is recorded before processing, so a failed processing attempt cannot be retried with the same nonce.
- The ledger consumer has no dead-letter topic; a poison message is logged and skipped after the default retries.
- Idempotency keys are global, not scoped per merchant, and never expire.
- The outbox publisher sends synchronously inside the DB transaction; one failed send re-publishes the whole batch (safe because consumers are idempotent, but wasteful).
- Single PostgreSQL instance hosts both databases for local development; the logical boundary is the database, not the server.

