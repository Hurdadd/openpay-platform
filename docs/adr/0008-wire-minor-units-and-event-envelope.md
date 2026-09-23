# ADR-0008: Minor-unit amounts and enveloped events on the wire

## Status
Accepted (PAY-6)

## Context
Settlement amounts crossed the orchestrator→ledger boundary as `BigDecimal` major units plus a
currency string. Two exponent tables disagreed about the same currency: the orchestrator's own
`Currency` enum says `IRR = 0` decimals, while `java.util.Currency` says `IRR = 2`. A consumer
converting with the JDK table turns `10000 IRR` into `1_000_000` minor units — a silent 100x
inflation. `USD`/`EUR` agree on both tables (`2`), so dollar-denominated tests never caught it.

Separately, the outbox payload carried no event identity and no event type. The consumer could not
tell a future `PaymentReversed`/`PaymentFailed` event apart from a settlement (Jackson 3 ignores
unknown fields and fills missing ones with `null`/`0` by default), and keying idempotency on
`paymentId` collides the moment a second event exists for the same payment.

## Decision
- Amounts travel as `long amountMinor` plus `currency`. The producer converts once, with its own
  registry, via `movePointRight(exponent)` + `longValueExact` — exact or loud, never silently
  rounded. The consumer performs zero conversion and stores `BIGINT`.
- `BigDecimal` stays in the pricing domain (`Money`, future fees/FX/rates). It is removed only
  from *posted* amounts. A ledger cannot post half a cent, so its wire unit is inherently integer.
- Every event travels as an envelope in the message value:
  `{eventId, eventType, data}`. `eventId` is the outbox row id, created at insert time (stable
  across publisher retries). `eventType` is the wire string (`PaymentSettled.v1`).
- Envelope-in-value beats headers here: no schema registry exists, replay/archival keeps metadata
  with data, and one model parses the whole fact. The Kafka record key stays `paymentId` — it is
  for partitioning/ordering, not idempotency.

## Consequences
- No conversion table is duplicated across services; the 100x class of bug cannot recur on this path.
- Redeliveries and future event types are safe: key = `eventId`, type checked before mapping.
- Display/reporting still needs an exponent registry — carrying minor units does not remove that;
  it only removes it from the money-movement path (open debt).
- The `canonical()` idempotency hash still hashes the raw `BigDecimal` string form, so `100.0`
  vs `100.00` hash differently (known bug, logged as debt to normalize on minor units).
