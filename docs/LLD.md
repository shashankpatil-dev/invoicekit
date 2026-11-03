# InvoiceKit LLD

| | |
|---|---|
| Status | Draft, document engine built |
| Date | 2026-11-01 |
| Related | [PRD](PRD.md), [HLD](HLD.md) |

## Build status

| Module | Status |
|---|---|
| `documentengine` | Complete for now, with the `subscriptions` template |
| `document-engine` | Complete for now |
| `numbering` | Complete, final load test pending. See [Numbering Design](NUMBERING.md) |
| `invoice`, `archive`, `adaption` | Not built yet |

The document engine still needs the `international` template and a full-pipeline load test. See [Load Test Results](LOAD_TEST.md).

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
document-engine/                    Typst rendering service
deploy/compose/                     Docker Compose for the whole stack
deploy/helm/<service>/              Helm chart, values.yaml plus values-<environment>.yaml
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
| Render `max.poll.records` | Small, tied to render concurrency |
| Render `max.poll.interval.ms` | Above the longest retry cycle |
| Producer `acks` | `all` |
| Producer `enable.idempotence` | `true` |

### `invoice.requested`

The full event, payload and field rules are in [Invoice Superset](INVOICE_SUPERSET.md).

### `invoice.render`

```json
{
  "schemaVersion": 1,
  "invoiceId": "018f3c1e-7b0a-7d3e-9e0a-2f4c9a6d1b11",
  "bu": "subscriptions",
  "sourceEventId": "sub-482:2026-11",
  "invoiceNumber": "SUBA260000000142",
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
  "bu": "subscriptions",
  "invoiceNumber": "SUBA260000000142",
  "uri": "gs://invoices/subscriptions/2026/11/01/018f3c1e-7b0a-7d3e-9e0a-2f4c9a6d1b11.pdf",
  "checksum": "crc32c:ab12cd34"
}
```

### DLQ headers

`x-error-code`, `x-error-message`, `x-source-topic`, `x-source-partition`, `x-source-offset`, `x-failed-at`.

## 3. Database

PostgreSQL 16+ with Flyway. All timestamps are `timestamptz`. Money is `numeric(18,2)`, never floating point.

```sql
CREATE TABLE gst_state (
  state_code  char(2) PRIMARY KEY,
  state_name  text NOT NULL
);

CREATE TABLE entity (
  entity_code                text PRIMARY KEY,
  legal_name                 text NOT NULL,
  trade_name                 text,
  gstin                      char(15) NOT NULL UNIQUE,
  state_code                 char(2) NOT NULL REFERENCES gst_state (state_code),
  address                    text NOT NULL,
  registered_office_address  text NOT NULL,
  cin                        text NOT NULL,
  pan                        text,
  lut_number                 text,
  authorised_signatory       text,
  bank_details               jsonb,
  contact_email              text,
  contact_phone              text,
  terms                      text,
  active                     boolean NOT NULL DEFAULT true
);

CREATE TABLE bu_entity (
  bu             text NOT NULL,
  entity_code    text NOT NULL REFERENCES entity (entity_code),
  number_shards  int NOT NULL DEFAULT 1 CHECK (number_shards BETWEEN 1 AND 26),
  PRIMARY KEY (bu, entity_code)
);

CREATE TABLE tax_code (
  tax_code        text NOT NULL,
  hsn_code        text NOT NULL,
  gst_rate        numeric(5, 2) NOT NULL,
  cess_rate       numeric(5, 2) NOT NULL DEFAULT 0,
  effective_from  date NOT NULL,
  effective_to    date,
  PRIMARY KEY (tax_code, effective_from)
);

CREATE TABLE declaration (
  code                  text NOT NULL,
  body                  text NOT NULL,
  bu                    text,
  supply_category       text CHECK (supply_category IN ('DOMESTIC', 'EXPORT_LUT')),
  reverse_charge        boolean,
  recipient_registered  boolean,
  sort_order            int NOT NULL,
  effective_from        date NOT NULL,
  effective_to          date,
  PRIMARY KEY (code, effective_from)
);

CREATE TABLE adapter_config (
  bu              text NOT NULL,
  version         int NOT NULL,
  allowed_fields  jsonb NOT NULL,
  number_format   text NOT NULL,
  active          boolean NOT NULL DEFAULT false,
  PRIMARY KEY (bu, version)
);

CREATE UNIQUE INDEX adapter_config_one_active_idx ON adapter_config (bu) WHERE active;

CREATE TABLE invoice (
  id                uuid PRIMARY KEY,
  bu                text NOT NULL,
  entity_code       text NOT NULL REFERENCES entity (entity_code),
  source_event_id   text NOT NULL,
  financial_year    text NOT NULL,
  invoice_date      date NOT NULL,
  invoice_number    varchar(16),
  shard             char(1),
  sequence_no       bigint,
  contract_version  int NOT NULL,
  payload           jsonb NOT NULL,
  status            text NOT NULL CHECK (status IN
                    ('NUMBERED', 'STORED', 'DELIVERED', 'FAILED', 'CANCELLED')),
  uri               text,
  checksum          text,
  attempts          int NOT NULL DEFAULT 0,
  notify_attempts   int NOT NULL DEFAULT 0,
  created_at        timestamptz NOT NULL DEFAULT now(),
  updated_at        timestamptz NOT NULL DEFAULT now(),
  UNIQUE (bu, source_event_id),
  UNIQUE (entity_code, bu, financial_year, shard, sequence_no),
  UNIQUE (entity_code, financial_year, invoice_number)
);

CREATE INDEX invoice_stuck_numbered_idx ON invoice (updated_at)
  WHERE status = 'NUMBERED';
CREATE INDEX invoice_stuck_stored_idx ON invoice (updated_at)
  WHERE status = 'STORED';

CREATE TABLE invoice_counter (
  entity_code     text NOT NULL REFERENCES entity (entity_code),
  bu              text NOT NULL,
  financial_year  text NOT NULL,
  shard           char(1) NOT NULL,
  last_no         bigint NOT NULL,
  PRIMARY KEY (entity_code, bu, financial_year, shard)
);

CREATE TABLE outbox (
  id           uuid PRIMARY KEY,
  invoice_id   uuid NOT NULL REFERENCES invoice (id),
  topic        text NOT NULL,
  message_key  text NOT NULL,
  body         jsonb NOT NULL,
  created_at   timestamptz NOT NULL DEFAULT now(),
  sent_at      timestamptz
);

CREATE INDEX outbox_unsent_idx ON outbox (created_at) WHERE sent_at IS NULL;

CREATE TABLE invoice_audit (
  id           bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  invoice_id   uuid NOT NULL REFERENCES invoice (id),
  from_status  text,
  to_status    text NOT NULL,
  actor        text NOT NULL,
  reason       text,
  created_at   timestamptz NOT NULL DEFAULT now()
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
    DUE_DATE, PAYMENT_TERMS, PO_NUMBER, SUBSCRIPTION_ID, PLAN_NAME, BILLING_CYCLE,
    COUPON_CODE, USAGE_SUMMARY, CONTRACT_REF, MILESTONE, ADDON_ID, DEVELOPER_ID
}
```

`allowed_fields` holds the BU's subset of these optional fields. Required fields in [Invoice Superset](INVOICE_SUPERSET.md) apply to every BU.

`AdapterRegistry` collects all `InvoiceAdapter` beans into a map by BU key.

Startup validation fails the process when:

- Two adapters claim the same BU.
- An active config names a field missing from `InvoiceField`.
- An adapter exists with no active config, or the reverse.
- The BU template is missing.
- `number_format` breaks a rule in [Numbering Design](NUMBERING.md#startup-validation), such as exceeding 16 characters or omitting `{shard}` when a BU has more than one shard.
- Two BUs under one entity share a number prefix.

Runtime rules:

- Reject any payload field outside `allowed_fields`.
- Tax amounts use `BigDecimal`, with rounding defined in shared code.
- Adapters do no I/O. Config and rates are passed in.
- Adapters never add declarations. See [Invoice Superset](INVOICE_SUPERSET.md).
- Adapters use the shared `TaxCalculator`, which applies the rounding rule in [Invoice Superset](INVOICE_SUPERSET.md).
- Tax rates are chosen by `taxCode` and `invoiceDate`, not by the current date.

## 6. Intake flow

```java
void onInvoiceRequested(ConsumerRecord<String, byte[]> record, Acknowledgment ack)
```

1. Parse the message. A parse failure goes to the DLQ with `INVALID_MESSAGE`.
2. Check Redis key `invoice:<bu>:<source_event_id>:done`. On a hit, ack and stop.
3. Find the invoice by `(bu, source_event_id)`. If it exists, set the done key where the status allows it, ack and stop. The outbox and cron own any remaining work.
4. Find the adapter. An unknown BU goes to the DLQ with `UNKNOWN_BU`.
5. Load config, entity, state, tax rates and declarations through the cache. A `bu_entity` miss goes to the DLQ with `ENTITY_NOT_ALLOWED`.
6. Run the adapter, then `DeclarationResolver`, which adds the matching declarations to the payload. Validation failures and unresolved placeholders go to the DLQ with `VALIDATION_FAILED`.
7. Run the numbering transaction.
8. Ack.

### Error classification

| Type | Examples | Action |
|---|---|---|
| Permanent | Bad schema, unknown BU, disallowed entity or field, unknown state, missing tax rate | DLQ, then ack |
| Transient | PostgreSQL or Kafka unavailable, timeouts | Retry with backoff, without ack |

The listener retries transient errors with exponential backoff, from 200ms up to 30s. It pauses the partition during long outages, so a dependency failure can't flood the DLQ.

## 7. Numbering transaction

The full design is in [Numbering Design](NUMBERING.md#6-the-allocation-transaction). Numbering owns only the counter statement. The invoice module owns the rest of the transaction:

```sql
BEGIN;

-- Invoice module: duplicates stop here, before the counter.
INSERT INTO invoice (...) VALUES (..., 'NUMBERED')
ON CONFLICT (bu, source_event_id) DO NOTHING
RETURNING id;
-- 0 rows: ROLLBACK, ack, stop. Another worker won.

INSERT INTO invoice_audit (invoice_id, to_status, actor)
VALUES (:invoiceId, 'NUMBERED', 'intake');

-- Numbering module: NumberingService.assign, which runs CounterLockSequenceCounter.
SET LOCAL lock_timeout = '2000ms';
SET LOCAL statement_timeout = '5000ms';
INSERT INTO invoice_counter AS c (entity_code, bu, financial_year, shard, last_no)
VALUES (:entityCode, :businessUnit, :financialYear, :shard, 1)
ON CONFLICT (entity_code, bu, financial_year, shard)
DO UPDATE SET last_no = c.last_no + 1
WHERE c.last_no < :maxSequence
RETURNING last_no;
-- 0 rows: the series is full, SeriesExhaustedException.

-- Invoice module: number the invoice and write the outbox row in one statement.
WITH numbered AS (
  UPDATE invoice
  SET shard = :shard, sequence_no = :sequenceNo, invoice_number = :invoiceNumber,
      payload = jsonb_set(payload, '{invoiceNumber}', to_jsonb(:invoiceNumber::text))
  WHERE id = :invoiceId
  RETURNING id
)
INSERT INTO outbox (id, invoice_id, topic, message_key, body)
SELECT :outboxId, id, 'invoice.render', id::text, :renderMessage FROM numbered;

COMMIT;
```

Why this order is safe:

- The invoice insert comes first, so a duplicate stops at the unique key and never touches the counter.
- A second worker with the same event waits for the first to commit, then inserts 0 rows.
- The counter is taken late, and only one statement follows it before commit.
- Each transaction locks one invoice key and then one counter row, always in that order, so it can't deadlock.
- A rollback undoes the counter too, so no number is lost.
- `NumberingService.assign` requires an existing transaction, so a number never commits alone.
- Timeouts stop a stuck session from blocking a series. Every pooled connection also sets `idle_in_transaction_session_timeout = 30s`.

### Series and format

The full design, with diagrams, is in [Numbering Design](NUMBERING.md).

- `SeriesKey` is `entity_code + bu + financial_year + shard`.
- **Shards:** a busy BU can be split into up to 26 series, `A` to `Z`, set by `bu_entity.number_shards`. Rule 46 allows multiple series. The shard letter is part of the number, so shards can never produce the same number.
- **Choosing a shard:** `hash(source_event_id) mod number_shards`, so the same event always lands in the same shard. Changing the shard count never renumbers an invoice, because duplicates are caught before a shard is chosen.
- **Capacity:** the `{seq:N}` width limits each series to `10^N - 1` numbers a year. Allocation fails rather than wrapping, and an alert fires at 80%. Adding shards is the fix.
- Each BU needs its own prefix. `UNIQUE (entity_code, financial_year, invoice_number)` protects uniqueness under a shared GSTIN.
- `financial_year` comes from `invoiceDate`, running 1 April to 31 March, and is saved on the invoice. Example: `2026-27`.
- `entity_code` comes from the event, and is saved so a retry can't change series.
- `number_format` is per BU, using the tokens `{shard}`, `{fy}` (two-digit starting year) and `{seq:N}`. The chosen format is `SUB{shard}{fy}{seq:10}`, which gives `SUBA260000000142`. It holds 9,999,999,999 numbers per series per year.
- Uniqueness is required per GSTIN, and each entity is one GSTIN, so the state doesn't need to appear in the number.
- Numbers are at most 16 characters, using only letters, digits, `-` and `/`. Startup validation rejects a format that can exceed this.

### Why not other allocators

| Option | Problem |
|---|---|
| PostgreSQL `SEQUENCE` | Rollbacks leave gaps |
| Redis `INCR` | Failover can move the counter backwards and reissue numbers |
| Reserved blocks | Crashes leave gaps, and numbers come out of order |
| Pool with `SKIP LOCKED` | Gap-free, but a rollback can issue numbers out of order. Kept only as a load test comparison |

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

A `503` from the Typst renderer follows this same path. Render retries use backoff with jitter, so workers don't all retry at the same moment.

### Document engine

```java
public interface DocumentEngine {
    RenderedDocument render(RenderRequest request);
}

public record RenderRequest(String businessUnit, String payloadJson) {}
public record RenderedDocument(byte[] pdf) {}
```

`payloadJson` stays a string, so the domain has no Jackson dependency.

`TypstDocumentEngine` calls the Typst renderer over HTTP. Its 25 second timeout stays below the service's 30 second worker limit, so the client fails first and the render can be retried.

| Response | Exception | Retried |
|---|---|---|
| `200` | None | No |
| `400`, `404`, `422` | `RenderRejectedException` | No, cron ends at `FAILED` |
| `5xx`, including `503` when the render queue is full, timeout, connection failure | `RenderUnavailableException` | Yes |

### Typst renderer

`document-engine` is a small Python service, using Typst 0.15.0 and gunicorn. It runs as its own container, so Typst doesn't share CPU or memory with the JVM.

| Endpoint | Purpose |
|---|---|
| `POST /render/<businessUnit>` | Renders the JSON body to a PDF |
| `GET /health` | Health check |

| Status | Meaning |
|---|---|
| `200` | `application/pdf` |
| `400` | Invalid JSON |
| `404` | No template for this BU |
| `422` | Compile error or any warning, with Typst's diagnostic |
| `503` | The render queue is full |

Rules:

- **Warm compilers:** one compiler per template is built at startup and reused.
- **Templates:** the image build copies `resources/adaption/`, so `<businessUnit>` is looked up in a fixed list and never builds a file path.
- **Data only:** the payload arrives through `sys.inputs.payload`, and templates read it with `json()`, so its values can't run as template code.
- **Fonts:** Source Sans 3 is bundled in the image, and system fonts are ignored.
- **Warnings fail:** Typst only warns about a missing font, so any warning fails the render.
- **Confinement:** file reads are limited to the assets directory, and no packages are downloaded.
- **Workers:** set `WEB_CONCURRENCY` to the container's CPU limit, because compiling is CPU-bound. Workers recycle after about 5,000 requests.
- **Threads:** each worker has 8 threads. One renders, up to 6 wait, and one stays free for `/health`, so readiness doesn't fail under overload.
- **Queue limit:** a 7th waiting render gets `503` immediately. Rendering stays one at a time per worker.
- **Watchdog:** a render running over 30 seconds makes the worker exit, and gunicorn starts a clean one. A stuck compile can't be cancelled from Python.
- **Upgrades:** Typst is pre-1.0, so its version is pinned, and any upgrade needs the output rechecked.

Load tests must use different data on every request, because Typst caches repeated work.

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
| `invoicekit:entity:<entity_code>` | Entity details | 5 min | Read PostgreSQL |
| `invoicekit:declarations` | Declaration rules | 5 min | Read PostgreSQL |
| `invoicekit:tax:<tax_code>:<date>` | Rate for the date | 1 hour | Read PostgreSQL |
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
  "bu": "subscriptions",
  "invoiceNumber": "SUBA260000000142",
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

Engineering-owned settings live in `.properties` files, one file per topic. Values read from the environment use `${NAME:default}`.

| File | Covers |
|---|---|
| `resources/config/server.properties` | HTTP port, virtual threads, monitoring |
| `resources/config/database.properties` | PostgreSQL and Flyway |
| `resources/config/kafka.properties` | Kafka client settings |
| `resources/config/redis.properties` | Redis connection |
| `resources/config/storage.properties` | Object storage and archive age |
| `resources/config/render.properties` | Typst renderer client |
| `resources/config/jobs.properties` | Outbox and recovery cron |
| `resources/config/notification.properties` | Mail server |
| `document-engine/config/renderer.properties` | Renderer queue, timeouts and gunicorn |

`application.yml` only imports these files and sets worker profiles. A new topic gets its own file. Renderer values can be overridden by environment variable, so `render.max-waiting` becomes `RENDER_MAX_WAITING`. The renderer refuses to start unless `server.threads` exceeds `render.slots + render.max-waiting`.

## 16. Observability

| Metric | Type |
|---|---|
| `invoice_step_duration_seconds{step,bu}` | Histogram |
| `invoice_status_total{status,bu}` | Gauge |
| `invoice_oldest_numbered_age_seconds` | Gauge |
| `outbox_oldest_unsent_age_seconds` | Gauge |
| `numbering_commit_duration_seconds{bu,region}` | Histogram |
| `typst_render_duration_seconds{bu}` | Histogram, measured by the Java client |
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
| Contract | Event schemas, OpenAPI, and real PDFs from the Typst renderer image, read back with PDFBox |
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

### CI

GitHub Actions runs `./gradlew build` on every push to `main` and every pull request, using Java 21. This covers Spotless formatting, compilation and all tests. See [Code Conventions](CONVENTIONS.md).

## 18. Implementation order

1. Gradle setup, Docker Compose, Flyway schema, ArchUnit rules.
2. Intake, first adapter, numbering transaction, concurrency test.
3. Polling outbox and render with Typst.
4. Storage, finish transaction, notification.
5. Recovery cron, DLQ replay, operations APIs.
6. Metrics and failure tests.
7. Second BU, `enterprise`, then `usage`, `services` and `marketplace`.
   `international` follows the core BUs.
8. Load test, then Debezium, then compare.
9. Multiple series test.
10. Archive and retrieval.

## 19. Open items

- Get the invoice superset reviewed by a CA.
- Confirm the cancellation process and retention period.
- Merge the bundling and retrieval design.
- Define API authentication.
- Test `ifGenerationMatch` on `fake-gcs-server`.
- Set partition counts and render concurrency from full-pipeline load tests.
- Find the cause of the 2 early failures in the gradual load test.
- Load test a saturated pod on Kubernetes with the overload changes.
- Rename `bu` to `businessUnit` in the schema, JSON contract and docs.
- Measure payload cost under load, including the String to bytes copy in `TypstDocumentEngine`. Consider Protobuf for Kafka messages only if the numbers justify it. PostgreSQL `jsonb` and Typst both still need JSON.
