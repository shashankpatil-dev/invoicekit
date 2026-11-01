# InvoiceKit LLD

| | |
|---|---|
| Status | Draft |
| Date | 2026-11-01 |
| Related | [PRD](PRD.md), [HLD](HLD.md) |

## 1. Code structure

Base package: `com.shashank.invoicekit`

```text
bootstrap/configuration/            Kafka, Redis, storage, scheduling
invoice/
  domain/entity/                    Invoice, InvoiceStatus, InvoicePayload
  domain/exception/
  application/service/              IntakeService, RenderService, RecoveryService
  application/adaption/             InvoiceAdapter, AdapterRegistry, InvoiceField
  application/port/outbound/        InvoiceRepository, OutboxWriter, FileStorage, Notifier
  adapter/inbound/                  REST, Kafka listeners, cron triggers
  adapter/kafka/                    Message classes
  adapter/outbound/postgres/
  adapter/outbound/gcs/
  adapter/outbound/notification/
numbering/
  domain/entity/                    SeriesKey, InvoiceNumber
  application/service/              NumberingService
  application/port/outbound/        SequenceCounter
  adapter/outbound/postgres/
documentengine/
  domain/entity/                    RenderRequest, RenderedDocument
  domain/exception/
  application/service/
  application/port/outbound/        DocumentEngine
  adapter/outbound/                 TypstDocumentEngine
archive/
  application/service/
  application/port/outbound/
  adapter/inbound/
  adapter/outbound/
adaption/<bu>/                      One adapter per BU
resources/adaption/<bu>/invoice.typ One template per BU
resources/db/migration/             Flyway migrations
```

Dependency rules, enforced with ArchUnit:

- `domain` depends on nothing outside itself and has no Spring imports.
- `application` depends only on `domain` and other modules' application contracts.
- `adapter` may depend on `application` and `domain`.
- `adaption` depends on `invoice`. `invoice` never imports `adaption`.
- `documentengine` never imports `invoice`.
- Outbound ports are interfaces, and outbound adapters implement them.

## 2. Kafka

### Topics

| Topic | Key | Partitions | Producer | Consumer |
|---|---|---|---|---|
| `invoice.requested` | `<bu>:<source_event_id>` | 24 | BU systems | Intake worker |
| `invoice.requested.dlq` | Same as source | 6 | Intake worker | Operators, replay tool |
| `invoice.render` | `invoice_id` | 24 | Outbox | Render worker |
| `invoice.generated` | `invoice_id` | 12 | Outbox | Notification consumer |

Partition counts are starting values, to be set from load tests. Keys must never be the BU alone, or one busy BU overloads a partition. Ordering between invoices isn't required.

Retention is 7 days, with 14 days for the DLQ. Replication factor is 3 in the cloud and 1 locally.

### Client settings

| Setting | Value |
|---|---|
| Consumer `enable.auto.commit` | `false` |
| Listener ack mode | Manual, after work is safe |
| Consumer `auto.offset.reset` | `earliest` |
| Render `max.poll.records` | Small, tied to Typst concurrency |
| Render `max.poll.interval.ms` | Above the longest retry cycle |
| Producer `acks` | `all` |
| Producer `enable.idempotence` | `true` |

### `invoice.requested`

```json
{
  "schemaVersion": 1,
  "bu": "logistics",
  "sourceEventId": "evt-101",
  "region": "MH",
  "invoiceDate": "2026-11-01",
  "currency": "INR",
  "buyer": { "name": "Acme Traders", "taxId": "27ABCDE1234F1Z5" },
  "seller": { "name": "Example Logistics", "taxId": "27AAAAA0000A1Z5" },
  "lineItems": [
    { "description": "Freight", "taxCode": "9965", "quantity": 1, "unitPrice": "1000.00" }
  ],
  "attributes": { "shipmentId": "SHP-778" }
}
```

### `invoice.render`

```json
{
  "schemaVersion": 1,
  "invoiceId": "018f3c1e-7b0a-7d3e-9e0a-2f4c9a6d1b11",
  "bu": "logistics",
  "sourceEventId": "evt-101",
  "invoiceNumber": "LGMH2627000142",
  "invoiceDate": "2026-11-01",
  "payload": { }
}
```

The payload travels inline, so render workers don't read PostgreSQL before rendering. Adapters reject payloads over 900KB.

Shortcut: inline payloads fit the 1MB Kafka default. Upgrade to a database reference if real invoices exceed the limit.

### `invoice.generated`

```json
{
  "schemaVersion": 1,
  "invoiceId": "018f3c1e-7b0a-7d3e-9e0a-2f4c9a6d1b11",
  "bu": "logistics",
  "invoiceNumber": "LGMH2627000142",
  "uri": "gs://invoices/logistics/2026/11/01/018f3c1e-7b0a-7d3e-9e0a-2f4c9a6d1b11.pdf",
  "checksum": "crc32c:ab12cd34"
}
```

### DLQ headers

`x-error-code`, `x-error-message`, `x-source-topic`, `x-source-partition`, `x-source-offset`, `x-failed-at`.

## 3. Database

PostgreSQL 16+ with Flyway. All timestamps are `timestamptz`. Money is `numeric(18,2)`, never floating point.

```sql
CREATE TABLE invoice (
  id                uuid PRIMARY KEY,
  bu                text NOT NULL,
  region            text NOT NULL,
  source_event_id   text NOT NULL,
  financial_year    text NOT NULL,
  invoice_date      date NOT NULL,
  invoice_number    text,
  sequence_no       bigint,
  contract_version  int NOT NULL,
  payload           jsonb NOT NULL,
  status            text NOT NULL CHECK (status IN
                    ('NUMBERED','STORED','DELIVERED','FAILED','CANCELLED')),
  uri               text,
  checksum          text,
  attempts          int NOT NULL DEFAULT 0,
  notify_attempts   int NOT NULL DEFAULT 0,
  created_at        timestamptz NOT NULL DEFAULT now(),
  updated_at        timestamptz NOT NULL DEFAULT now(),
  UNIQUE (bu, source_event_id),
  UNIQUE (bu, region, financial_year, sequence_no)
);

CREATE INDEX invoice_stuck_numbered_idx ON invoice (updated_at)
  WHERE status = 'NUMBERED';
CREATE INDEX invoice_stuck_stored_idx ON invoice (updated_at)
  WHERE status = 'STORED';

CREATE TABLE invoice_counter (
  bu              text NOT NULL,
  region          text NOT NULL,
  financial_year  text NOT NULL,
  last_no         bigint NOT NULL,
  PRIMARY KEY (bu, region, financial_year)
);

CREATE TABLE outbox (
  id             uuid PRIMARY KEY,
  invoice_id     uuid NOT NULL REFERENCES invoice (id),
  topic          text NOT NULL,
  message_key    text NOT NULL,
  body           jsonb NOT NULL,
  created_at     timestamptz NOT NULL DEFAULT now(),
  sent_at        timestamptz
);

CREATE INDEX outbox_unsent_idx ON outbox (created_at) WHERE sent_at IS NULL;

CREATE TABLE adapter_config (
  bu               text NOT NULL,
  version          int NOT NULL,
  allowed_fields   jsonb NOT NULL,
  number_format    text NOT NULL,
  active           boolean NOT NULL DEFAULT false,
  PRIMARY KEY (bu, version)
);

CREATE UNIQUE INDEX adapter_config_one_active_idx ON adapter_config (bu) WHERE active;

CREATE TABLE tax_rate (
  tax_code        text NOT NULL,
  region          text NOT NULL,
  rate            numeric(6,3) NOT NULL,
  effective_from  date NOT NULL,
  effective_to    date,
  PRIMARY KEY (tax_code, region, effective_from)
);

CREATE TABLE invoice_audit (
  id          bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  invoice_id  uuid NOT NULL REFERENCES invoice (id),
  from_status text,
  to_status   text NOT NULL,
  actor       text NOT NULL,
  reason      text,
  created_at  timestamptz NOT NULL DEFAULT now()
);
```

Notes:

- `invoice_number` and `sequence_no` are nullable only because the row is inserted before the counter update. Both are set before commit, so other transactions never see them as `NULL`. An integration test asserts this.
- Monthly partitioning of `invoice` is deferred. PostgreSQL requires the partition key in unique constraints, so adopting it needs a separate design for the idempotency key.
- The `invoice_id` is a UUIDv7, so ordering stays index-friendly.

## 4. Status machine

```text
                   step 5           render finish          notification
 (no row) ---------------> NUMBERED ------------> STORED ---------------> DELIVERED
                            |   ^
             5 cron attempts|   | manual re-drive, attempts = 0
                            v   |
                           FAILED ----- manual cancel -----> CANCELLED
```

| From | To | Trigger |
|---|---|---|
| None | `NUMBERED` | Numbering transaction |
| `NUMBERED` | `STORED` | Render finish, guarded |
| `STORED` | `DELIVERED` | Notification sent |
| `NUMBERED` | `FAILED` | Recovery attempts exhausted |
| `FAILED` | `NUMBERED` | Manual re-drive |
| `FAILED` | `CANCELLED` | Manual cancellation, audit required |

Every transition writes an `invoice_audit` row in the same transaction.

## 5. Adapter contract

```java
public interface InvoiceAdapter {
    String bu();
    InvoicePayload build(InvoiceRequested event, AdapterConfig config, TaxRates taxRates);
}
```

`InvoiceField` is the superset: the catalog of every field the platform supports.

```java
public enum InvoiceField {
    BUYER, SELLER, LINE_ITEMS, TAX_BREAKUP, SHIPMENT_ID, VEHICLE_NUMBER, STORE_CODE, DISCOUNTS
}
```

`allowed_fields` holds the BU's subset. The example field names are placeholders until real BUs are defined.

`AdapterRegistry` collects all `InvoiceAdapter` beans into a map by BU key.

Startup validation fails the process when:

- Two adapters claim the same BU.
- An active config names a field missing from `InvoiceField`.
- An adapter exists with no active config, or the reverse.
- The BU template is missing.
- `number_format` has no sequence placeholder.

Runtime rules:

- Reject any payload field outside `allowed_fields`.
- Tax amounts use `BigDecimal`, with rounding defined in shared code.
- Adapters do no I/O. Config and rates are passed in.
- Adapters use the shared `TaxCalculator`, so tax logic isn't duplicated.
- Tax rates are chosen by `invoiceDate`, not by the current date.

## 6. Intake flow

```java
void onInvoiceRequested(ConsumerRecord<String, byte[]> record, Acknowledgment ack)
```

1. Parse the message. A parse failure goes to the DLQ with `INVALID_MESSAGE`.
2. Check Redis key `invoice:<bu>:<source_event_id>:done`. On a hit, ack and stop.
3. Find the invoice by `(bu, source_event_id)`. If it exists, set the done key where the status allows it, ack and stop. The outbox and cron own any remaining work.
4. Find the adapter. An unknown BU goes to the DLQ with `UNKNOWN_BU`.
5. Load config and rates through the cache.
6. Run the adapter. Validation failures go to the DLQ with `VALIDATION_FAILED`.
7. Run the numbering transaction.
8. Ack.

### Error classification

| Type | Examples | Action |
|---|---|---|
| Permanent | Bad schema, unknown BU, disallowed field, missing tax rate | DLQ, then ack |
| Transient | PostgreSQL or Kafka unavailable, timeouts | Retry with backoff, without ack |

The listener retries transient errors with exponential backoff, from 200ms up to 30s. It pauses the partition during long outages, so a dependency failure can't flood the DLQ.

## 7. Numbering transaction

Keep this transaction short, with no network calls other than PostgreSQL.

```sql
BEGIN;

INSERT INTO invoice (id, bu, region, source_event_id, financial_year,
                     invoice_date, contract_version, payload, status)
VALUES (:id, :bu, :region, :eventId, :fy, :date, :version, :payload, 'NUMBERED')
ON CONFLICT (bu, source_event_id) DO NOTHING;
-- 0 rows: ROLLBACK, ack, stop. Another worker won.

INSERT INTO invoice_counter (bu, region, financial_year, last_no)
VALUES (:bu, :region, :fy, 1)
ON CONFLICT (bu, region, financial_year)
DO UPDATE SET last_no = invoice_counter.last_no + 1
RETURNING last_no;

UPDATE invoice
SET sequence_no = :lastNo, invoice_number = :formatted
WHERE id = :id;

INSERT INTO invoice_audit (invoice_id, to_status, actor)
VALUES (:id, 'NUMBERED', 'intake');

INSERT INTO outbox (id, invoice_id, topic, message_key, body)
VALUES (:outboxId, :id, 'invoice.render', :id, :renderMessage);

COMMIT;
```

Why this order is safe:

- The insert comes first, so a duplicate stops at the unique key and never touches the hot counter row.
- A second worker with the same key waits for the first to commit, then inserts 0 rows.
- The counter lock is held for the smallest possible window, and each transaction touches one counter row, so it can't deadlock on counters.
- A rollback undoes the counter too, so no number is lost.

### Series and format

- `SeriesKey` is `bu + region + financial_year`.
- `financial_year` comes from `invoiceDate` using the rules for the region, and is saved on the invoice. Example: `2026-27`.
- `region` comes from the event, and is saved so a retry can't change series.
- `number_format` is per BU. Example: `LG{region}{fy}{seq:6}` gives `LGMH2627000142`.
- Format length limits are to be confirmed per country.

### Why not other allocators

| Option | Problem |
|---|---|
| PostgreSQL `SEQUENCE` | Rollbacks leave gaps |
| Redis `INCR` | Failover can move the counter backwards and reissue numbers |
| Reserved blocks | Crashes leave gaps, and numbers come out of order |

## 8. Outbox publishing

### Phase 1: polling

Runs in the `jobs` profile, every 100ms.

```sql
SELECT id, topic, message_key, body
FROM outbox
WHERE sent_at IS NULL
ORDER BY created_at
LIMIT 500
FOR UPDATE SKIP LOCKED;
```

Publish the batch, wait for acks, set `sent_at`, then commit. A crash after publishing can resend rows, so delivery is at-least-once. A cleanup job deletes sent rows after 24 hours.

### Phase 2: Debezium CDC

- Set `wal_level=logical`.
- Run a Debezium PostgreSQL connector with the Outbox Event Router, reading `outbox`.
- Route to Kafka by the `topic` column, keyed by `message_key`.
- Set `outbox.mode=cdc`, so `OutboxWriter` deletes the row in the same transaction it was inserted. The WAL still carries the insert, and the table stays nearly empty.
- Keep message formats the same, so render workers don't change.

### CDC operating rules

- Set `max_slot_wal_keep_size`, so a dead connector can't fill the disk.
- Alert on slot lag.
- If the slot is lost, rebuild it, and the recovery cron re-drives anything stuck in `NUMBERED`. `STORED` rows are re-driven the same way.

## 9. Render flow

```java
void onInvoiceRender(ConsumerRecord<String, byte[]> record, Acknowledgment ack)
```

1. Take the lease: `SET invoice:<bu>:<source_event_id>:render <workerId> NX EX 120`.
2. If the lease is held, ack and stop. Cron covers a dead holder.
3. If Redis is unavailable, continue without a lease.
4. Render with `DocumentEngine`, retrying twice with backoff.
5. Upload with create-only semantics.
6. Run the finish transaction.
7. Set the done key and release the lease.
8. Ack.

If steps 4 to 6 still fail, release the lease and ack. The row stays `NUMBERED` for cron. The full retry cycle stays under 60 seconds.

### Document engine

```java
public interface DocumentEngine {
    RenderedDocument render(RenderRequest request);
}

public record RenderRequest(String bu, String invoiceNumber, JsonNode payload) {}
public record RenderedDocument(byte[] pdf) {}
```

`TypstDocumentEngine` steps:

1. Create a temp directory per render.
2. Write `payload.json`, and copy the BU template and assets.
3. Run `typst compile --root <dir> invoice.typ invoice.pdf`, with no shell.
4. Enforce a 30 second timeout and kill the process on expiry.
5. Read the PDF and always delete the directory.

Templates read data with `json("payload.json")`, so payload values stay data and can't execute as template code.

A semaphore limits concurrent Typst processes to the number of CPU cores. Fonts are bundled in the image, so output doesn't depend on the host.

Process startup per render is a known cost, and the load test will measure it. If it dominates, evaluate a long-running process or an embedded Typst library.

### Storage

Object path: `invoices/<bu>/<yyyy>/<MM>/<dd>/<invoice_id>.pdf`

```java
public interface FileStorage {
    StoredObject putIfAbsent(String path, byte[] content, String contentType);
    StoredObject stat(String path);
    String signedDownloadUrl(String path, Duration ttl);
}
```

Upload uses GCS precondition `ifGenerationMatch=0`. On a precondition failure, the object already exists, so `stat` returns its checksum and the flow continues. Two workers can't replace each other's output, so the saved checksum always matches the stored object.

`fake-gcs-server` support for this precondition must be tested in M1.

### Finish transaction

```sql
BEGIN;

UPDATE invoice
SET status = 'STORED', uri = :uri, checksum = :checksum, updated_at = now()
WHERE id = :id AND status = 'NUMBERED';
-- 0 rows: ROLLBACK, ack. Another worker finished.

INSERT INTO invoice_audit (invoice_id, from_status, to_status, actor)
VALUES (:id, 'NUMBERED', 'STORED', 'render');

INSERT INTO outbox (id, invoice_id, topic, message_key, body)
VALUES (:outboxId, :id, 'invoice.generated', :id, :generatedMessage);

COMMIT;
```

## 10. Notification flow

1. Consume `invoice.generated`.
2. Skip if the status is already `DELIVERED`.
3. Send through the `Notifier` port, with `invoice_id` as the idempotency key where the provider supports it.
4. Run `UPDATE invoice SET status = 'DELIVERED' WHERE id = :id AND status = 'STORED'`.
5. Ack.

Locally, the adapter sends by SMTP to Mailpit. On failure it retries briefly, then acks. Cron re-drives from `STORED`.

A crash between sending and step 4 can send twice unless the provider dedupes. This is an accepted, at-least-once limit.

## 11. Recovery cron

Runs in the `jobs` profile every minute. Cron always re-drives through the outbox, so there is a single render path.

```sql
WITH claimed AS (
  SELECT id FROM invoice
  WHERE status = 'NUMBERED'
    AND updated_at < now() - interval '5 minutes'
    AND attempts < 5
  ORDER BY updated_at
  LIMIT 100
  FOR UPDATE SKIP LOCKED
)
UPDATE invoice i
SET attempts = attempts + 1, updated_at = now()
FROM claimed
WHERE i.id = claimed.id
RETURNING i.*;
```

In the same transaction, it inserts one `invoice.render` outbox row per claimed invoice.

A second query moves rows with `attempts >= 5` to `FAILED`, writes audit rows and raises an alert.

For `STORED` rows idle over 15 minutes, it republishes `invoice.generated`, using `notify_attempts`. At 5, it alerts and leaves the status as `STORED`, because the invoice itself is valid.

The 5 minute threshold must stay above the 120 second lease and the 60 second retry cycle.

## 12. Redis

| Key | Value | TTL | If missing |
|---|---|---|---|
| `invoicekit:config:<bu>` | Active config | 5 min | Read PostgreSQL |
| `invoicekit:tax:<region>:<date>` | Rates for the date | 1 hour | Read PostgreSQL |
| `invoice:<bu>:<source_event_id>:done` | `1` | 24 hours | Read PostgreSQL |
| `invoice:<bu>:<source_event_id>:render` | Worker ID | 120 sec | Render anyway |

The done key is written only after a PostgreSQL commit. Lease release uses a compare-and-delete Lua script, so a worker can't release another worker's lease. A failed Redis call is logged and treated as a miss, and never fails the invoice.

## 13. APIs

Base path: `/api/v1`. All endpoints need authentication.

| Method | Path | Purpose |
|---|---|---|
| `GET` | `/invoices/{id}` | Metadata and status |
| `GET` | `/invoices?bu=&sourceEventId=` | Find by event |
| `GET` | `/invoices/{id}/document` | Signed download URL, or archived retrieval |
| `POST` | `/ops/invoices/{id}/redrive` | `FAILED` to `NUMBERED`, attempts reset, outbox row added |
| `POST` | `/ops/invoices/{id}/cancel` | `FAILED` to `CANCELLED`, reason required |
| `POST` | `/ops/dlq/replay` | Replay selected DLQ events |

`/ops` endpoints need an operator role, and write `invoice_audit` with the actor and reason.

Example response:

```json
{
  "id": "018f3c1e-7b0a-7d3e-9e0a-2f4c9a6d1b11",
  "bu": "logistics",
  "invoiceNumber": "LGMH2627000142",
  "status": "STORED",
  "invoiceDate": "2026-11-01",
  "createdAt": "2026-11-01T10:15:30Z"
}
```

Error format is `application/problem+json`, with codes `INVOICE_NOT_FOUND`, `INVALID_STATE` and `FORBIDDEN`.

Signed URLs expire in 15 minutes.

## 14. Archive and retrieval

The detailed bundling and retrieval design already exists and will be merged here. These constraints apply:

- The daily job selects only `STORED` or `DELIVERED` invoices older than 90 days.
- Each bundle must be resumable after a crash.
- Originals are deleted only after the bundle's checksum is verified.
- The API looks up each invoice's location, then reads it from Standard or from its bundle.
- Single-invoice retrieval needs each invoice's location inside the bundle.
- At full average load, a daily bundle is about 86GB at 50KB per PDF, so very large days may need splitting.
- Costs must include Archive's minimum storage period and retrieval fees.

## 15. Configuration

```yaml
invoicekit:
  outbox:
    mode: polling        # polling or cdc
    poll-interval: 100ms
    batch-size: 500
  render:
    timeout: 30s
    max-concurrency: 4
    lease-ttl: 120s
  recovery:
    interval: 1m
    numbered-stuck-after: 5m
    stored-stuck-after: 15m
    max-attempts: 5
    batch-size: 100
  archive:
    older-than-days: 90
```

## 16. Observability

| Metric | Type |
|---|---|
| `invoice_step_duration_seconds{step,bu}` | Histogram |
| `invoice_status_total{status,bu}` | Gauge |
| `invoice_oldest_numbered_age_seconds` | Gauge |
| `outbox_oldest_unsent_age_seconds` | Gauge |
| `numbering_commit_duration_seconds{bu,region}` | Histogram |
| `typst_render_duration_seconds{bu}` | Histogram |
| `redis_fallback_total{operation}` | Counter |
| `dlq_events_total{bu,error_code}` | Counter |
| `replication_slot_lag_bytes` | Gauge |

The `step` label values are `adapter`, `numbering`, `publish`, `render`, `upload`, `finish` and `notify`.

`traceparent` flows through Kafka headers, so one trace covers a full invoice.

## 17. Testing

| Layer | Coverage |
|---|---|
| Unit | Adapters, tax, number formats, financial year, status rules |
| Architecture | ArchUnit module and layer rules |
| Integration | Testcontainers for Kafka, PostgreSQL and Redis, plus `fake-gcs-server` |
| Contract | Event schemas, OpenAPI |
| Concurrency | Parallel numbering, checked for no duplicates or gaps |
| Load | Sustained 20 rps, 100 rps burst, single hot series |

### Required failure tests

| Test | Pass condition |
|---|---|
| Send each event 3 times | One invoice each |
| Kill an intake worker mid-transaction | No gap or duplicate |
| Kill a render worker mid-render | Invoice completes with the same number |
| Stop storage during a run | All complete after restart |
| Stop Redis during a run | No failures, only slower |
| Stop Debezium mid-run | Catches up with no invoice lost |
| Publish a bad event | DLQ, no number used |
| Delete a message after numbering | Cron completes the invoice |

After each test, a reconciliation query must confirm that each series runs from 1 to `last_no` with no missing or repeated numbers, and that every `STORED` invoice has an object.

## 18. Implementation order

1. Gradle setup, Docker Compose, Flyway schema, ArchUnit rules.
2. Intake, first adapter, numbering transaction, concurrency test.
3. Polling outbox and render with Typst.
4. Storage, finish transaction, notification.
5. Recovery cron, DLQ replay, operations APIs.
6. Metrics and failure tests.
7. Second BU.
8. Load test, then Debezium, then compare.
9. Multiple series test.
10. Archive and retrieval.

## 19. Open items

- Confirm number format, length limits and financial year rules per country.
- Confirm the cancellation process and retention period.
- Merge the bundling and retrieval design.
- Define API authentication.
- Test `ifGenerationMatch` on `fake-gcs-server`.
- Set partition counts and render concurrency from load tests.
