# Intake Design

| | |
|---|---|
| Status | Design approved, not built |
| Date | 2026-11-10 |
| Related | [Invoice Superset](INVOICE_SUPERSET.md), [Numbering Design](NUMBERING.md), [LLD](LLD.md), [PRD](PRD.md) FR1-FR13 |

Intake turns one `invoice.requested` event into one numbered invoice, saved with its payload and an outbox row for rendering. This document covers what intake guarantees, the flow, the adapter contract, how the payload is built, the transaction, errors, the schema, and the four build steps.

## 1. Scope

| In this module | Later modules |
|---|---|
| Kafka listener for `invoice.requested`, with retries and the dead-letter topic | Outbox publisher, which sends `invoice.render` to Kafka |
| Event parsing and validation | Render worker, storage, notification |
| Adapter contract, registry, startup checks, and the `subscriptions` adapter | Other BU adapters: `usage`, `enterprise`, `services`, `marketplace`, `international` |
| Tax, totals, amount in words and declarations | Recovery cron, DLQ replay, operations APIs |
| The intake transaction: duplicate check, number, invoice, audit and outbox | Archive |
| Caffeine cache for reference data, and tracing | |

## 2. Guarantees

| Guarantee | How |
|---|---|
| One invoice per event | `invoice_event` holds `(bu, source_event_id)` as its key, and is inserted first in the transaction |
| No number for a bad event | Everything is validated and calculated before the transaction opens. Bad events go to the dead-letter topic |
| No lost invoice | The invoice and its outbox row commit together, and the Kafka offset is committed only after that |
| Old invoices never change | The payload stores a snapshot of entity, tax and declaration values |
| A busy series stays fast | The counter is locked late, and only one statement follows it before commit |

## 3. Flow

```text
Kafka: invoice.requested  (key = <bu>:<sourceEventId>, value = JSON)
   |
   v
+--------------------------------------------------------------------------+
| INTAKE LISTENER                       invoice/adapter/inbound             |
|   parse JSON                     bad JSON           -> DLQ INVALID_MESSAGE|
+--------------------------------------------------------------------------+
   |
   v
+--------------------------------------------------------------------------+
| PREPARE  (no transaction, no locks)   invoice/application/service         |
|                                                                           |
|  1. Validate the envelope          missing or wrong -> DLQ VALIDATION_FAILED
|  2. Find the adapter               unknown BU       -> DLQ UNKNOWN_BU     |
|  3. Load reference data (Caffeine)                                        |
|       entity, BU access            not allowed      -> DLQ ENTITY_NOT_ALLOWED
|       adapter config, state, tax codes, declarations                     |
|  4. Adapter maps the event to an InvoiceDraft                             |
|       field outside allowed_fields                  -> DLQ VALIDATION_FAILED
|  5. Assemble the payload                                                  |
|       supplier snapshot, place of supply, supply type,                    |
|       tax per line, totals, amount in words, declarations                 |
|       missing tax rate, unresolved placeholder      -> DLQ VALIDATION_FAILED
+--------------------------------------------------------------------------+
   |
   v
+--------------------------------------------------------------------------+
| COMMIT  (one PostgreSQL transaction)                                      |
|                                                                           |
|  6. INSERT invoice_event ON CONFLICT DO NOTHING                           |
|       0 rows: a duplicate. Commit nothing, ack, stop.                     |
|  7. NumberingService.assign        counter row locked here                |
|       series full                                   -> DLQ SERIES_EXHAUSTED
|  8. One statement: INSERT invoice (with number and payload),              |
|     INSERT invoice_audit, INSERT outbox (invoice.render)                  |
|  COMMIT                            counter lock released                  |
+--------------------------------------------------------------------------+
   |
   v
 Ack the Kafka offset
```

Steps 1 to 5 hold no database locks, so slow work never blocks a series. A duplicate stops at step 6, before the counter, and no number is used for anything rejected earlier.

### Changes from the earlier LLD flow

| Earlier plan | Now | Why |
|---|---|---|
| Insert the invoice first, then number it, then update it | Insert `invoice_event` first, then number, then insert the invoice once | The large payload is written once instead of twice, and duplicate detection moves to a small table that can stay unique when `invoice` is partitioned |
| Redis `done` key before PostgreSQL | No Redis in intake | The `invoice_event` key already rejects duplicates cheaply. Redis would add a dependency without adding correctness |
| Redis cache for reference data | Caffeine in each pod | Reference data is small and rarely changes, so an in-process cache is faster and has nothing to run |

## 4. Event model

The event follows [Invoice Superset](INVOICE_SUPERSET.md) contract v2. It is parsed with Jackson into immutable records:

```text
InvoiceRequested
  schemaVersion, businessUnit (JSON "bu"), sourceEventId, entityCode, invoiceDate,
  supplyCategory, placeOfSupplyStateCode, reverseCharge, currency, exchangeRate,
  dueDate, paymentTerms, poNumber
  recipient: Recipient
  lineItems: List<LineItem>
  attributes: Map<String, String>
```

Validation is one class, `InvoiceRequestValidator`, which collects every problem in one pass, so the dead-letter message lists them all. It checks the superset rules: required fields, `schemaVersion` 2, `DOMESTIC` needs a place of supply and `INR`, exports need a country and an exchange rate and no reverse charge, GSTIN format, decimals within their scale, and at least one line.

## 5. Adapter contract

Each BU has one adapter. It maps that BU's event into a common `InvoiceDraft`, with final line values: quantity, unit price, discount and service period. Proration, tiers and coupons are worked out here, so the rest of the platform never knows about them.

```java
public interface InvoiceAdapter {

  /** The BU this adapter serves, such as "subscriptions". */
  String businessUnit();

  /** Turns the BU's event into final invoice lines and extras. No I/O. */
  InvoiceDraft adapt(InvoiceRequested event);
}
```

| Part | Responsibility |
|---|---|
| `InvoiceAdapter` | One per BU, in `adaption/<bu>/`. No I/O, no tax, no declarations |
| `AdapterRegistry` | Finds the adapter by BU key |
| `InvoiceField` | The catalog of optional fields, such as `PLAN_NAME` and `PO_NUMBER` |
| `AdapterConfig` | The BU's active row in `adapter_config`: `allowed_fields` and `number_format` |
| `AllowedFieldsCheck` | Rejects any optional field or attribute the BU's config doesn't allow |

### Startup checks

The intake worker refuses to start when:

- Two adapters claim the same BU.
- An adapter has no active config, or an active config has no adapter.
- A config names a field missing from `InvoiceField`.
- A config's `number_format` doesn't parse.
- Two BUs that share an entity in `bu_entity` share a number prefix. This is the prefix check left open by numbering.
- A BU in `bu_entity` uses more than 1 shard with a format that has no `{shard}`.

### The `subscriptions` adapter

Its events already carry final seat counts and coupon discounts, so the adapter mainly passes lines through. It also checks its own rules: `billingCycle` is `MONTHLY` or `ANNUAL`, and every line has a service period.

## 6. Building the payload

`InvoiceAssembler` turns the draft plus reference data into the saved payload, exactly matching the shape in [Invoice Superset](INVOICE_SUPERSET.md#saved-payload). It delegates each rule to one focused class:

| Class | Rule |
|---|---|
| `SupplyTypeRule` | `EXPORT` for `EXPORT_LUT`. Otherwise `INTRA_STATE` when the entity's state equals the place of supply, or `INTER_STATE` |
| `TaxCalculator` | Per line: taxable value, then CGST and SGST at half the rate each, or IGST at the full rate, or nothing for exports, plus cess. Every amount is rounded to 2 decimals with `HALF_UP`, and totals add up the rounded line values |
| `AmountInWords` | Indian grouping (lakh, crore) for `INR`, such as "Rupees Seventeen Thousand One Hundred Ten Only". International grouping for other currencies, with paise or cents when present |
| `DeclarationResolver` | Picks the declarations whose conditions all match, fills placeholders such as `{lutNumber}`, and keeps their print order. An unresolved placeholder rejects the event |
| `SupplierSnapshot` | Copies the entity's fields into the payload, so later edits don't change old invoices |

Money is always `BigDecimal`. All rates come from `tax_code` for the invoice date, never the current date.

## 7. The intake transaction

Prepared outside the transaction: the invoice ID, the full payload without its number, the render message, and the numbering request.

```sql
BEGIN;

-- 1. Duplicate check. 0 rows: another delivery already created this invoice.
INSERT INTO invoice_event (bu, source_event_id, invoice_id, financial_year)
VALUES (:businessUnit, :sourceEventId, :invoiceId, :financialYear)
ON CONFLICT (bu, source_event_id) DO NOTHING;

-- 2. NumberingService.assign: SET LOCAL timeouts, then the counter upsert.

-- 3. One statement for everything that needs the number.
WITH saved AS (
  INSERT INTO invoice (id, bu, entity_code, source_event_id, financial_year, invoice_date,
                       invoice_number, shard, sequence_no, contract_version, payload, status)
  VALUES (:invoiceId, :businessUnit, :entityCode, :sourceEventId, :financialYear,
          :invoiceDate, :invoiceNumber, :shard, :sequenceNo, 2, :payload, 'NUMBERED')
  RETURNING id
), audited AS (
  INSERT INTO invoice_audit (invoice_id, to_status, actor)
  SELECT id, 'NUMBERED', 'intake' FROM saved
)
INSERT INTO outbox (id, invoice_id, topic, message_key, body)
SELECT :outboxId, id, 'invoice.render', id::text, :renderMessage FROM saved;

COMMIT;
```

- The payload and render message already contain the invoice number, which Java adds after step 2. Nothing is updated afterwards.
- The lock order is always the event key, then one counter row, so transactions can't deadlock.
- `IntakeTransaction` is the only class that opens this transaction, and `NumberingService.assign` refuses to run outside it.

## 8. Errors

| Code | Cause | Action |
|---|---|---|
| `INVALID_MESSAGE` | JSON that can't be parsed | Dead-letter topic, then ack |
| `VALIDATION_FAILED` | Broken superset rule, disallowed field, missing tax rate, unknown state, unresolved placeholder | Dead-letter topic, then ack |
| `UNKNOWN_BU` | No adapter for the BU | Dead-letter topic, then ack |
| `ENTITY_NOT_ALLOWED` | No `bu_entity` row for the BU and entity | Dead-letter topic, then ack |
| `SERIES_EXHAUSTED` | The series has no numbers left | Dead-letter topic, then ack. An operator adds shards and replays |
| Transient | PostgreSQL down, timeouts, `SeriesBusyException` | Retry with backoff from 200ms to 30s, without ack, until it succeeds |

- Permanent errors are one exception type, `RejectedEventException`, carrying the code and every reason.
- Spring Kafka's `DefaultErrorHandler` sends it straight to `invoice.requested.dlq`, adding the `x-error-code` and `x-error-message` headers next to its own source topic, partition and offset headers.
- Every other exception is transient and retried, so the partition waits instead of flooding the dead-letter topic while the database is down.

## 9. Schema

**Decision for approval:** partition `invoice` now, while it holds no data. At 1 crore orders a day it gains about 3.65B rows a year, and partitioning an existing table later means copying all of it.

PostgreSQL requires the partition key inside every unique key. That shapes the design:

| Table | Partitioning | Why it stays correct |
|---|---|---|
| `invoice` | By `financial_year`, one partition per year | Both number keys already include `financial_year`, so they stay fully unique. The primary key becomes `(id, financial_year)` |
| `invoice_event` (new) | By hash of `source_event_id`, 16 partitions | Its key `(bu, source_event_id)` contains the partition key, so duplicate detection stays fully unique forever |

Other changes:

- **Duplicate key moves:** `invoice.invoice_source_event_unique` moves to `invoice_event`, which is the only place a duplicate is caught.
- **Foreign keys dropped:** `outbox.invoice_id` and `invoice_audit.invoice_id` lose their foreign keys, because a foreign key to a partitioned table must include the partition key. The transaction writes all three rows together, so they can't disagree.
- **Partitions created ahead:** the migration creates partitions for 2025-26 to 2030-31. A test fails when the next year's partition is missing, so it can't be forgotten.
- **`invoice_numbered_together`:** this check stays, but invoices are now always inserted numbered.
- **Limit:** one partition per year is about 3.65B rows by year 10. If that is too large, monthly sub-partitions would need the numbering safety-net keys replaced by a reconciliation job. Not needed for this plan.

Reference data migration (`V3`): all GST states, the `SAAS_18` tax code, and the `subscriptions` adapter config. Entities are company data, so they stay in the test series and local seeds, never in migrations.

## 10. Configuration

`config/intake.properties`:

| Setting | Value | Why |
|---|---|---|
| `invoicekit.intake.concurrency` | `4` | Consumer threads per pod. Pods × threads must stay at or below the 24 partitions, and below the pool size |
| `invoicekit.intake.retry-initial-interval` | `200ms` | First retry wait for transient errors |
| `invoicekit.intake.retry-max-interval` | `30s` | Longest wait between retries |
| `invoicekit.intake.reference-cache-ttl` | `5m` | How long Caffeine keeps reference data |
| `invoicekit.intake.max-payload-bytes` | `900000` | Keeps `invoice.render` under Kafka's 1MB default |

Tracing goes in `config/tracing.properties`: the sampling rate and the OTLP endpoint. Jaeger joins the Compose stack so traces can be viewed locally.

## 11. Code structure

```text
invoice/
  domain/entity/            InvoiceRequested, Recipient, LineItem, InvoiceDraft, InvoicePayload,
                            SupplyCategory, SupplyType, InvoiceField, Money helpers
  domain/exception/         RejectedEventException, RejectionCode
  application/adaption/     InvoiceAdapter, AdapterRegistry, AdapterConfig, AllowedFieldsCheck
  application/service/      IntakeService, InvoiceRequestValidator, InvoiceAssembler,
                            TaxCalculator, AmountInWords, DeclarationResolver, IntakeTransaction
  application/port/outbound ReferenceData, InvoiceStore
  adapter/inbound/          InvoiceRequestedListener
  adapter/kafka/            Kafka and error handler setup
  adapter/outbound/postgres CachedReferenceData, PostgresInvoiceStore
adaption/subscriptions/     SubscriptionsAdapter
bootstrap/configuration/    IntakeConfiguration, IntakeProperties, tracing setup
```

Architecture rules, added to ArchUnit:

- The invoice domain has no Spring, JDBC or Kafka code.
- Adapters in `adaption/` depend only on the invoice domain and the adapter contract.
- `invoice` never imports `adaption`, so a new BU is only new files.
- `numbering` still never imports `invoice`.

## 12. Testing

| Test | Proves |
|---|---|
| Validator unit tests | Every superset rule, and that all problems are reported together |
| `TaxCalculator`, `AmountInWords`, `DeclarationResolver`, `SupplyTypeRule` unit tests | Intra-state, inter-state and export tax, rounding, Indian and international words, declaration matching and placeholders |
| `InvoiceAssembler` test | The domestic example event produces exactly the saved payload in the superset doc |
| `SubscriptionsAdapter` and registry tests | Field rules, the prefix check, and every startup failure |
| `IntakeTransactionTest` (PostgreSQL) | A new event saves the invoice, audit and outbox rows together. A duplicate saves nothing and uses no number. A failure after numbering leaves no gap |
| Partition tests | Invoices land in the right yearly partition, duplicates are caught across partitions, and next year's partition exists |
| `InvoiceRequestedListenerTest` (Kafka + PostgreSQL) | An event becomes a numbered invoice and an outbox row, a bad event reaches the DLQ with its headers, and a database outage is retried without loss |
| Dummy events | 1, then 10 `subscriptions` events through Kafka on the local stack, with the rows checked in PostgreSQL |
| Concurrency | Many events at once, including repeats, still give one invoice each and gap-free series |

## 13. Build steps

Each step ends with a passing build, its own short commits, and a push.

| Step | Builds | Proves |
|---|---|---|
| **1. Schema and reference data** | Partitioned `invoice`, `invoice_event`, foreign key changes, the `V3` reference data, updated numbering test helpers | Schema and partition tests, plus all existing numbering tests passing on the new tables |
| **2. Domain and payload rules** | Event records, validator, `InvoiceField`, adapter contract, registry with startup checks, `SubscriptionsAdapter`, tax, words, declarations, assembler | Unit tests, including the exact superset example payload |
| **3. Intake transaction** | Reference data with Caffeine, `IntakeTransaction`, `IntakeService`, wiring | PostgreSQL integration tests for saving, duplicates, rollback and concurrency |
| **4. Kafka, errors and tracing** | Listener, `DefaultErrorHandler` with the DLQ, retries, tracing, Jaeger in Compose, docs | Kafka integration tests, then 1 and 10 dummy events on the local stack |

## 14. Risks and open points

- **Payload contract review:** the superset still needs a CA's review. Its export example shows an old number format, which step 2 corrects.
- **Year rollover:** partitions must exist before each financial year. The test catches a missing one, but production also needs the next migration shipped in time.
- **Retry forever:** a transient error that never clears holds its partition. Lag alerts must catch it.
- **Only one BU:** only `subscriptions` gets an adapter now. The contract is shaped by its needs, and the next adapters will test it.
