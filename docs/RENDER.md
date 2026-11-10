# Outbox and Render Design

| | |
|---|---|
| Status | Design approved, not built |
| Date | 2026-11-10 |
| Related | [Intake Design](INTAKE.md), [LLD](LLD.md) sections 8 and 9, [HLD](HLD.md), [Load Test Results](LOAD_TEST.md) |

Intake ends with a numbered invoice and an `invoice.render` row in the outbox. This document covers the two pieces that turn that row into a stored PDF: the **outbox publisher**, which moves outbox rows to Kafka, and the **render worker**, which renders the PDF, stores it, and marks the invoice `STORED`.

## 1. Scope

| In this module | Later |
|---|---|
| Outbox publisher by polling, in the `jobs` profile, and cleanup of sent rows | Debezium change data capture as the outbox transport |
| Trace context carried through the outbox, so one trace covers intake to render | Notification consumer for `invoice.generated` |
| Render worker in the `render` profile: render, store, finish | Recovery cron and operations APIs |
| Object storage adapter for GCS, with fake-gcs-server locally | Signed download URLs and the invoice API |
| Finish transaction: `STORED`, audit and an `invoice.generated` outbox row | Archive |
| Removing Redis, which nothing uses any more | |

## 2. Guarantees

| Guarantee | How |
|---|---|
| Every committed outbox row reaches Kafka | The publisher only marks a row sent after Kafka acknowledges it. A crash before that resends it |
| A resent message does no harm | The render worker checks the invoice's status first, uploads only if the object is absent, and finishes only from `NUMBERED` |
| One stored PDF per invoice | The upload is create-only, so a second worker can never replace the first file, and the saved checksum always matches the stored object |
| Nothing lost while the renderer or storage is down | Those failures are retried with backoff and never acknowledged early |
| A broken template doesn't loop forever | A render the engine rejects marks the invoice `FAILED`, with the reason in its audit row |

## 3. Flow

```text
 PostgreSQL outbox (invoice.render rows, written by intake)
    |
    v
+--------------------------------------------------------------------------+
| OUTBOX PUBLISHER                 outbox module, jobs profile, every 100ms |
|                                                                           |
|  BEGIN                                                                    |
|    claim up to 500 unsent rows    FOR UPDATE SKIP LOCKED                  |
|    send each to its topic          key and trace headers kept             |
|    wait for Kafka's acks                                                  |
|    mark the acknowledged rows sent                                        |
|  COMMIT                                                                   |
+--------------------------------------------------------------------------+
    |
    v
 Kafka: invoice.render  (key = invoice ID, payload inline)
    |
    v
+--------------------------------------------------------------------------+
| RENDER WORKER                    invoice module, render profile           |
|                                                                           |
|  1. Parse the message              unreadable    -> log, ack, skip        |
|  2. Read the invoice's status      not NUMBERED  -> ack, skip (done already)
|  3. Render with the document engine                                       |
|       engine rejects the payload   -> mark FAILED with the reason, ack    |
|       engine unavailable           -> retry with backoff                  |
|  4. Store the PDF, only if absent                                         |
|       already there                -> use the existing file's checksum    |
|       storage down                 -> retry with backoff                  |
|  5. One statement: status STORED, uri, checksum, audit row,               |
|     and an invoice.generated outbox row, only WHERE status = NUMBERED     |
|  6. Ack                                                                   |
+--------------------------------------------------------------------------+
    |
    v
 PostgreSQL outbox (invoice.generated rows)  ->  same publisher  ->  Kafka
```

The render worker never holds a database transaction while rendering or uploading. Its only transaction is the final statement in step 5.

### Changes from the earlier LLD plan

| Earlier plan | Now | Why |
|---|---|---|
| Redis lease before rendering | A status check in PostgreSQL | One cheap primary key lookup catches nearly every resent message. Two workers rendering the same invoice at the same moment is rare and harmless, because the upload is create-only and the finish is guarded |
| Retry twice in the worker, then leave the invoice for the recovery cron | Retry with backoff until it works, like intake | A renderer or storage outage affects every invoice equally, so waiting is the right behaviour. Nothing depends on the recovery cron, which stays a safety net for later |
| Engine rejection ends at `FAILED` through the cron | Marked `FAILED` immediately | A rejected payload or broken template will fail the same way every time. Failing fast tells an operator straight away |
| Redis cache and leases | No Redis anywhere | Intake replaced its uses with Caffeine and `invoice_event`, and this design replaces the lease. Removing it drops a service, a dependency and a health check that reports down when Redis isn't running |

## 4. Outbox publisher

A small, generic module that knows nothing about invoices.

```text
outbox/
  domain/entity/            OutboxMessage (id, topic, key, body, headers)
  application/port/outbound OutboxRepository, MessageBus
  application/service/      OutboxRelay, OutboxCleanup
  adapter/outbound/postgres PostgresOutboxRepository
  adapter/outbound/kafka    KafkaMessageBus
```

### Publishing a batch

```sql
SELECT id, topic, message_key, body, headers
FROM outbox
WHERE sent_at IS NULL
ORDER BY created_at
LIMIT :batchSize
FOR UPDATE SKIP LOCKED;
```

- **Batch transaction:** the claim, the sends and the update all happen in one transaction, so a crash before commit releases the rows and they're sent again.
- **Send, then wait:** all messages in the batch are sent, then the relay waits for every acknowledgement, up to a send timeout. Rows Kafka acknowledged are marked sent with one `UPDATE ... WHERE id = ANY(:ids)`. Rows that failed stay unsent for the next poll.
- **Several pods:** `SKIP LOCKED` lets several publisher pods run at once without ever claiming the same row.
- **Order:** rows go out roughly in creation order. Nothing depends on order, because each consumer checks state itself.
- **Delivery:** at least once. A crash after Kafka acknowledged a send, but before commit, sends that row again, which consumers already tolerate.

### Cleanup

`OutboxCleanup` deletes sent rows older than `invoicekit.outbox.retention` (24 hours), in batches of `invoicekit.outbox.cleanup-batch-size`, every `invoicekit.outbox.cleanup-interval`. The table stays small, and recent rows remain for investigation.

### Trace context

Intake already runs inside a trace. Without help, that trace ends at the outbox, and the render worker starts a new one. So intake stores the current W3C `traceparent` in a new `headers` column, and the publisher sends it as a Kafka header. Spring Kafka's tracing then continues the same trace in the render worker. One trace covers intake, publishing and rendering.

## 5. Render worker

The render worker belongs to the invoice module, because it moves an invoice from `NUMBERED` to `STORED`.

### Message

`invoice.render`, as intake writes it today:

```json
{
  "schemaVersion": 1,
  "invoiceId": "...",
  "bu": "subscriptions",
  "sourceEventId": "sub-482:2026-11",
  "invoiceNumber": "SUBA260000000142",
  "invoiceDate": "2026-11-01",
  "payload": { "financialYear": "2026-27", "...": "..." }
}
```

The worker reads `payload.financialYear` so every database statement on `invoice` includes the partition key, which lets PostgreSQL go straight to one partition.

### Classes

| Class | Responsibility |
|---|---|
| `InvoiceRenderListener` (inbound adapter) | Parses the message into a `RenderJob`, calls `RenderService`, then acknowledges |
| `RenderService` | Steps 2 to 5 of the flow. No transaction of its own |
| `RenderFinish` | The only transactional class: the guarded finish statement |
| `DocumentEngine` (existing port, document engine module) | Renders the PDF |
| `DocumentStore` (new port) | Stores bytes at a path if absent, and returns the URI and checksum |
| `GcsDocumentStore` (adapter) | Google Cloud Storage client, pointed at fake-gcs-server locally |
| `InvoiceLifecycleStore` (new port) | Reads an invoice's status, finishes it, and marks it `FAILED` |

### Storing the PDF

Path: `invoices/<bu>/<yyyy>/<MM>/<dd>/<invoice_id>.pdf`, using the invoice date.

The upload uses the GCS precondition `ifGenerationMatch=0`, meaning "create only". If the object already exists, GCS answers `412`, and the store reads the existing object's CRC32C checksum instead. So two workers racing on one invoice store one file, and both record the same checksum.

The checksum is saved as `crc32c:<base64>`, the form GCS reports.

### Finish statement

```sql
WITH finished AS (
  UPDATE invoice
  SET status = 'STORED', uri = :uri, checksum = :checksum, updated_at = now()
  WHERE id = :invoiceId AND financial_year = :financialYear AND status = 'NUMBERED'
  RETURNING id, bu, invoice_number
), audited AS (
  INSERT INTO invoice_audit (invoice_id, from_status, to_status, actor)
  SELECT id, 'NUMBERED', 'STORED', 'render' FROM finished
)
INSERT INTO outbox (id, invoice_id, topic, message_key, body, headers)
SELECT :outboxId, id, 'invoice.generated', id::text, :generatedMessage, :headers FROM finished;
```

If the update matches no row, another worker finished first, or the invoice is `FAILED` or `CANCELLED`. Nothing is inserted, and the message is simply acknowledged.

`invoice.generated` carries `schemaVersion`, `invoiceId`, `bu`, `invoiceNumber`, `uri` and `checksum`, as in [LLD](LLD.md) section 2.

### Marking a render failed

When the engine rejects a payload, a guarded update sets `FAILED` only from `NUMBERED`, and the audit row stores the engine's diagnostic as the reason. The invoice keeps its number, as compliance requires. An operator can fix the template and re-drive it later through the operations APIs.

## 6. Errors

| Failure | Exception | Action |
|---|---|---|
| Unreadable `invoice.render` message | Parse error | Log at ERROR, ack, skip. It can only come from a bug, and its invoice stays `NUMBERED` for the recovery cron |
| Engine rejects the payload (`400`, `404`, `422`) | `RenderRejectedException` | Mark `FAILED` with the reason, ack |
| Engine unavailable (`5xx`, `503` when its queue is full, timeout) | `RenderUnavailableException` | Retry with backoff from 1s to 30s, no ack |
| Storage unavailable | Storage exception | Same retry |
| PostgreSQL unavailable | Database exception | Same retry |

The retry is Spring Kafka's `DefaultErrorHandler`, as in intake, but with no dead-letter topic: there is no message here that could never succeed. Waits start at 1s, so a busy renderer gets breathing room instead of an instant repeat.

## 7. Capacity

From the document engine load test, one renderer pod with 1 CPU handles about 75 PDFs a second, and accepts 7 requests at once (1 rendering and 6 waiting).

| Setting | Rule |
|---|---|
| Render worker threads per pod (`invoicekit.render.concurrency`) | Pods × threads ≤ renderer pods × 7, or the renderer answers `503` and work is retried |
| `invoice.render` partitions (24) | Pods × threads ≤ 24 |
| Renderer pods | Add one per ~75 PDFs a second of peak, plus one spare |

## 8. Schema (`V5`)

- `outbox.headers jsonb`, holding trace headers such as `traceparent`.
- A partial index `outbox_sent_idx ON outbox (sent_at) WHERE sent_at IS NOT NULL`, so cleanup finds old sent rows without scanning unsent ones.
- No other changes: `invoice` already has `status`, `uri` and `checksum`.

## 9. Configuration

| File | Setting | Value |
|---|---|---|
| `jobs.properties` | `invoicekit.outbox.poll-interval` | `100ms` |
| | `invoicekit.outbox.batch-size` | `500` |
| | `invoicekit.outbox.send-timeout` | `10s` |
| | `invoicekit.outbox.retention` | `24h` |
| | `invoicekit.outbox.cleanup-interval` | `10m` |
| | `invoicekit.outbox.cleanup-batch-size` | `5000` |
| `render.properties` | `invoicekit.render.concurrency` | `4`, replacing `max-concurrency` |
| | `invoicekit.render.retry-initial-interval` | `1s` |
| | `invoicekit.render.retry-max-interval` | `30s` |
| `storage.properties` | `invoicekit.storage.endpoint`, `invoicekit.storage.bucket` | Already present |
| `redis.properties` | Whole file | Removed with Redis |

`invoicekit.render.lease-ttl` and the unused `invoicekit.outbox.mode` are removed. The mode comes back with Debezium.

## 10. Testing

| Test | Proves |
|---|---|
| `OutboxRelayTest` (PostgreSQL + Kafka) | Unsent rows reach their topics with key and headers, and are marked sent. A failed send stays unsent. Two relays running together never send a row twice |
| `OutboxCleanupTest` | Only sent rows older than the retention are deleted, in batches |
| `GcsDocumentStoreTest` (fake-gcs-server) | Create-only upload, the existing object's checksum on a second upload, and the path layout |
| `RenderServiceTest` (unit, fakes) | Skip when not `NUMBERED`, `FAILED` on rejection, retryable errors thrown unchanged |
| `RenderFinishTest` (PostgreSQL) | `STORED`, audit and outbox rows together. A second finish changes nothing |
| `InvoiceRenderListenerTest` (Kafka + PostgreSQL + fake-gcs + a stub renderer) | A message becomes a stored PDF and an `invoice.generated` row. A resent message renders nothing. An unavailable renderer is retried until it recovers |
| End to end on the local stack | Events sent to `invoice.requested` become PDFs in fake-gcs-server, invoices `STORED`, `invoice.generated` messages on Kafka, and one Jaeger trace per invoice from intake to render |

## 11. Build steps

Each step ends with a passing build and small commits.

| Step | Builds |
|---|---|
| **1. Outbox publisher** | `V5`, the outbox module, the relay and cleanup on schedules in the `jobs` profile, trace headers from intake, tests |
| **2. Document storage** | `DocumentStore` port, `GcsDocumentStore`, storage settings, fake-gcs-server tests |
| **3. Render service** | `RenderJob`, `RenderService`, `RenderFinish`, the lifecycle store, failure marking, tests |
| **4. Render worker, Redis removal, docs** | Kafka listener and error handling in the `render` profile, Redis removed, docs, then the end-to-end run on the local stack |

## 12. Risks

- **fake-gcs-server preconditions:** create-only uploads must behave like real GCS. Step 2 proves it before anything relies on it.
- **Outbox transaction length:** the batch transaction stays open while waiting for Kafka, up to the send timeout. A slow Kafka holds 500 row locks for that long, which only delays other publishers.
- **Retry forever:** as with intake, an error that never clears holds its partition. Lag alerts must catch it.
- **Recovery cron not built:** an invoice whose `invoice.render` message is lost by a bug stays `NUMBERED` until the cron exists.
