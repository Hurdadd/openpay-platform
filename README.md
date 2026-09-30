<div align="center">

# OpenPSP

### Payment orchestration, with the failure paths in view.

From an idempotent payment request to an append-only double-entry ledger.

[![Java 21](https://img.shields.io/badge/Java-21-2563eb?style=flat-square)](build.gradle.kts)
[![Spring Boot](https://img.shields.io/badge/Spring_Boot-4.x-16a34a?style=flat-square)](payment-orchestrator/bootstrap/build.gradle.kts)
[![PostgreSQL 17](https://img.shields.io/badge/PostgreSQL-17-336791?style=flat-square)](docker-compose.yml)
[![Kafka](https://img.shields.io/badge/Kafka-3.9_KRaft-6366f1?style=flat-square)](docker-compose.yml)
[![Status](https://img.shields.io/badge/Status-in_development-d97706?style=flat-square)](#where-the-project-stands)

[Explore the architecture](docs/architecture/README.md) · [Engineering reference](docs/engineering.md) · [Design decisions](docs/adr/README.md) · [فارسی](README.fa.md)

</div>

## The problem OpenPSP explores

A merchant retries a request. A payment provider times out after accepting it. A callback arrives late. A consumer commits to the database, crashes, and receives the same event again.

These are the moments a payment system has to explain: **what happened to the payment, and did the ledger record it once?**

OpenPSP separates those responsibilities. The **payment orchestrator** owns the lifecycle and provider interaction; the **ledger service** owns balanced accounting entries. Their intended integration contract is a versioned settlement event, `PaymentSettled.v1`.

The project implements these mechanisms in Java 21 using ports and adapters, PostgreSQL, Redis and Kafka. The full request-to-settlement journey is still being connected; the map and status below make those gaps explicit.

## See the system

![OpenPSP architecture: merchant requests, callback verification, payment storage, status polling, settlement events and ledger recording](docs/architecture/openpsp.svg)

**[Explore the interactive map →](docs/architecture/README.md)**

Generated with [Archify](https://github.com/tt-a1i/archify) from the repository at `78a204a`. The standalone HTML includes component details and links to the inspected source. GitHub shows the file as source; download it and open it in a browser to explore it.

Two logical databases keep payment state and accounting separate. Local Compose runs them in one PostgreSQL instance. The configured PSP is the in-process `SlowPsp` simulator; `psp-gateway` is a placeholder.

## What makes the design interesting

| When this happens | The mechanism | The current boundary |
|---|---|---|
| A client retries a payment | An idempotency key and canonical body hash select a stored response or a key conflict | Store and use case exist; merchant UUID JSON binding needs integration work |
| A payment receives an invalid transition | A framework-free aggregate permits only explicit state changes | Illegal transitions are rejected; callback handling ignores repeated or disallowed statuses |
| A callback is forged or replayed | HMAC covers timestamp, nonce and exact body; Redis claims the nonce | Nonce consumption currently precedes processing, which limits same-nonce retries |
| The provider does not answer | A reconciler checks old `PENDING_PSP` payments and leaves unknown outcomes pending | Polling exists; the create flow does not yet authorize or enter `PENDING_PSP` |
| An event must reach accounting | An outbox publisher sends existing rows to Kafka, keyed by payment ID | Settlement and outbox insertion still need one atomic transaction |
| Kafka delivers the same settlement again | A unique event ID makes journal insertion a no-op on duplicates | The ledger commits its transaction before acknowledging the message |
| Code tries to alter or unbalance the ledger | PostgreSQL rejects updates/deletes and checks journal completeness and balance at commit | At least two postings, signed integer minor units, sum equal to zero |

Transport duplication is expected. The design uses idempotent processing at specific boundaries rather than claiming exactly-once delivery.

## Follow one settlement

The intended journey reads as three responsibilities:

1. **Accept the intent.** Store a payment and an idempotent response; authorize with the provider and preserve uncertainty when it does not answer.
2. **Resolve the lifecycle.** Verify provider callbacks, reconcile missing responses, and write a settlement event with the state transition.
3. **Record the obligation.** Deliver `PaymentSettled.v1` through Kafka; debit PSP clearing and credit the merchant payable account in one ledger transaction.

The first two steps still have integration gaps. The ledger side already contains the event parser, duplicate guard, balanced postings and database constraints.

For a settlement of **10,000 minor units**, the journal records:

| Account | Posting |
|---|---:|
| PSP clearing — amount receivable from the provider | +10,000 |
| Merchant payable — amount owed to the merchant | −10,000 |
| **Sum** | **0** |

This journal records an accounting obligation; it does not transfer funds or execute a merchant payout. The [event sample](docs/events/PaymentSettled.v1.json) defines the shared envelope and integer amount.

## Where the project stands

**In development.** The repository contains focused tests for payment transitions, optimistic locking, HMAC verification, replay protection, reconciliation, outbox publication and ledger redelivery after a crash. A complete failure-injection test covering the whole payment journey remains unfinished.

The next integration milestones are concrete:

- Bind the merchant UUID at the HTTP boundary and connect create → PSP authorization → `PENDING_PSP`.
- Save the settlement transition and outbox event in the same transaction.
- Make callback replay handling compatible with processing failures and out-of-order delivery.
- Add the end-to-end fault-injection flow and dead-letter handling for the ledger consumer.

Further tradeoffs—including global idempotency keys, synchronous outbox sends and provider retry settings—are documented in the [engineering reference](docs/engineering.md#known-limitations).

## Run locally

You need **JDK 21** and **Docker with Compose v2**.

```bash
git clone https://github.com/Hurdadd/openpay-platform.git
cd openpay-platform
docker compose up -d
```

Run each service in its own terminal:

```bash
./gradlew :payment-orchestrator:bootstrap:bootRun  # localhost:8080
./gradlew :ledger-service:bootstrap:bootRun        # localhost:8081
```

Compose provides PostgreSQL, Redis, Kafka and topic initialization. Flyway applies each service's migrations at startup. See [configuration and request examples](docs/engineering.md#getting-started); those request examples describe target behavior while the HTTP and PSP integrations are unfinished.

Run the existing checks with Docker available:

```bash
./gradlew build
```

The [CI workflow](.github/workflows/ci.yml) validates Compose and builds both adapters and runnable bootstraps. Domain and application tests are separate modules; `./gradlew build` includes them as well.

## Navigate the code

| Area | Owns |
|---|---|
| [`payment-orchestrator`](payment-orchestrator) | Payment aggregate, use cases, REST API, callbacks, provider adapter, reconciliation and outbox publication |
| [`ledger-service`](ledger-service) | Settlement consumption, journal construction, JDBC persistence and ledger constraints |
| [`psp-gateway`](psp-gateway) | Placeholder Spring Boot application |
| [`docs/adr`](docs/adr/README.md) | The reasons behind state transitions, boundaries, callback security and accounting decisions |
| [`docs/engineering.md`](docs/engineering.md) | Detailed sequence diagrams, domain and data models, settings, tests and limitations |

The orchestrator follows `bootstrap → adapter → application → domain`, with framework-free domain and application logic. The ledger declares the same four Gradle modules, but its use case and ports currently live under `adapter`; the extraction into its application module is unfinished.

Start with [`CreatePaymentService`](payment-orchestrator/application/src/main/java/com/github/hurdadd/application/service/CreatePaymentService.java), then [`PspCallbackService`](payment-orchestrator/application/src/main/java/com/github/hurdadd/application/service/PspCallbackService.java), and finally [`RecordLedgerEntryService`](ledger-service/adapter/src/main/java/com/github/hurdadd/adapter/application/RecordLedgerEntryService.java).
