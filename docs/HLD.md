# InvoiceKit HLD

| | |
|---|---|
| Status | Draft, document engine built |
| Date | 2026-11-01 |
| Related | [PRD](PRD.md), [LLD](LLD.md) |

## 1. Overview

InvoiceKit is a single Spring Boot codebase that runs as separate processes. Kafka carries work, PostgreSQL holds the truth, and Redis only saves effort.

The system has two stages, split at numbering:

1. **Commit stage:** consume the event, run the adapter, assign a number, and commit.
2. **Generation stage:** render, store and notify.

After the commit stage, an invoice must be completed. Every later failure is a delay, never a loss.

## 2. Architecture

```text
 +-----------+   invoice.requested   +------------------------------------+
 | BU system | --------------------> | Kafka                              |
 +-----------+                       +------------------------------------+
                                        |                         ^  ^
                                        v                         |  |
                       +---------------------------------+        |  |
          +------------| INTAKE WORKER                   |        |  |
          |            |  Listener                       |        |  |
          |  invalid   |  BU adapter: validate, tax      |--------+  |
          v            |  Numbering                      | (Phase 2  |
 +------------------+  +---------------------------------+  via CDC) |
 | invoice.requested|       |                |                       |
 | .dlq             |       | cache          | one transaction       |
 +------------------+       v                v                       |
                       +---------+   +----------------------+        |
                       | Redis   |   | PostgreSQL           |        |
                       +---------+   | invoice, counter,    |        |
                            ^        | outbox, config, tax  |        |
                            |        +----------------------+        |
                            |                |        WAL            |
                            |                v                       |
                            |        +----------------------+        |
                            |        | Outbox publisher     |--------+
                            |        | Polling, then Debezium        |
                            |        +----------------------+
                            |
                            | render lease             invoice.render
                            |                                |
                       +---------------------------------+   |
                       | RENDER WORKER                   |<--+
                       |  Typst + BU template            |
                       |  Upload                         |-----> Object storage
                       |  Guarded status update          |-----> PostgreSQL
                       +---------------------------------+

 invoice.generated ---> NOTIFICATION CONSUMER ---> Provider (Mailpit locally)
 RECOVERY CRON -------> Re-drive stuck invoices through the outbox
 ARCHIVE JOB ---------> Bundle old invoices, move to Archive storage
 INVOICE API ---------> Status, download, operations
```

## 3. Components

| Component | Responsibility | Scales by |
|---|---|---|
| Intake worker | Consume events, run the adapter, assign the number | Partitions and pods |
| BU adapter | Validate allowed fields, calculate tax, build the payload | In-process, stateless |
| Numbering | Allocate the next number in a series | Number of series |
| Outbox publisher | Move committed events to Kafka | Polling batches, then Debezium |
| Render worker | Call the Typst renderer, upload, update status | Partitions and pods |
| Typst renderer | Compile the BU template and payload into a PDF, and refuse extra work with `503` | CPU and workers |
| Notification consumer | Send the notification, set `DELIVERED` | Partitions and pods |
| Recovery cron | Re-drive stuck invoices | Batches, one claim per row |
| Archive job | Bundle and move old invoices | Daily batches |
| Invoice API | Status, download, re-drive, cancel | Stateless pods |

## 4. Run modes

The same application starts in one mode per deployment, chosen by Spring profile:

| Profile | Runs |
|---|---|
| `api` | Invoice API |
| `intake` | Intake worker |
| `render` | Render worker |
| `notify` | Notification consumer |
| `jobs` | Outbox poller, recovery cron, archive job |

Typst and fonts live only in the separate Typst renderer image. Locally, all profiles can run together.

## 5. End-to-end flow

```text
 1  Kafka delivers invoice.requested to the intake worker
 2  Check Redis for a done key, then check PostgreSQL
 3  Load config, entity and tax rates (Redis, falling back to PostgreSQL)
 4  Adapter validates and builds the payload
       invalid: dead-letter topic, no number used
 5  One transaction: invoice + counter + number + payload + outbox
 6  Commit the Kafka offset
    ------------------ invoice must now be completed ------------------
 7  Outbox publisher sends invoice.render
 8  Render worker takes a Redis lease
 9  Typst renders the PDF
10  Create the object in storage, only if it doesn't exist
11  One transaction: status STORED + outbox invoice.generated
12  Commit the Kafka offset
13  Notification consumer sends the notification, sets DELIVERED
```

## 6. Key decisions

| Decision | Choice | Why | Rejected |
|---|---|---|---|
| Source of truth | PostgreSQL | Queryable, transactional, auditable | Kafka: no lookups, and a dual-write gap |
| Internal queue | Kafka `invoice.render` | Render scales separately and has backpressure | Redis queue: not durable enough. In-process: no separate scaling |
| Reliable publish | Transactional outbox | Commit and event succeed together | Publish after commit: a crash loses the event |
| Outbox transport | Polling first, then Debezium CDC | Polling is quick to build. CDC removes polling, and the comparison gets measured | CDC only: no baseline. Polling only: delay and bloat at scale |
| Numbering | Counter row per entity, BU and financial year | Transactional, with no rollback gaps | `SEQUENCE`: gaps. Redis `INCR`: can reissue after failure. Blocks: gaps and disorder |
| Dedupe | Unique key in PostgreSQL, Redis as a fast path | Correct even if Redis is empty | Redis only: not safe |
| Render dedupe | Redis lease plus guarded update | Saves CPU, and the database remains correct without Redis | Database lock during render: holds a connection too long |
| Post-number failure | Recovery cron reads PostgreSQL | The invoice can't be dropped, and the cron still works if a message is lost | DLQ: invoices could be forgotten |
| Document engine | Typst only | Lightweight, and no browser to run | Chromium: heavy |
| Typst runtime | Long-running service with warm compilers | Avoids process startup per invoice, and keeps CPU-heavy work out of the JVM | CLI per render: slower |
| Templates | BU side, one per BU | Each BU owns its layout | Shared central templates |
| Storage write | Create-only | A duplicate can't replace a stored invoice | Overwrite: stored checksum could stop matching |

## 7. Redis rule

Redis may hold anything that can be rebuilt from PostgreSQL, and nothing that only exists in Redis.

| Allowed | Not allowed |
|---|---|
| Adapter config, entity, tax rate and declaration cache | Sequence numbers |
| Done markers for dedupe | Invoice status as the truth |
| Render leases | The render queue |

If Redis is down, the system reads PostgreSQL and accepts some duplicate rendering.

## 8. Failure handling

| Failure | Behaviour | Outcome |
|---|---|---|
| Duplicate event | Dedupe check, or the unique key inserts 0 rows | One invoice |
| Two workers race | Second waits on the unique key, then stops | Counter untouched by the loser |
| Crash during the commit stage | Transaction rolls back | No number lost |
| Crash before outbox publish | Row stays in the outbox or WAL | Published after restart |
| Debezium or poller down | Numbering continues, and rendering waits | Catches up, or cron re-drives |
| Render worker crash | Lease expires, Kafka redelivers or cron re-drives | Same number |
| Typst or storage outage | Retries, then the offset commits and cron takes over | Completes later |
| Duplicate render | Create-only upload and guarded update | One object, one finish |
| Redis down | Reads PostgreSQL, no lease | Slower |
| PostgreSQL down | Intake stops and Kafka backlog grows | Resumes on recovery |
| Kafka down | Producers fail, while the outbox holds committed work | Resumes on recovery |
| Notification failure | Status stays `STORED`, cron re-drives | Delayed notification |
| 5 failed recoveries | `FAILED`, alert | Manual re-drive or cancellation |

## 9. Scaling

| Bottleneck | Approach |
|---|---|
| Intake | Add partitions and pods |
| Render | Scale render workers and Typst renderer workers, with workers matched to CPU |
| Hot series | Keep the transaction short, split into more series where rules allow |
| PostgreSQL writes | Connection pool limits, partition `invoice` by month, keep outbox small |
| Publish delay | Move from polling to CDC |
| Burst | Kafka buffers the backlog |

The expected first limits are the busiest counter row and Typst CPU. Load tests will confirm this.

## 10. Data lifecycle

```text
 0 to 90 days      Standard storage, one PDF per invoice
 Over 90 days      Daily tar bundle
 After bundling    Archive storage, verified originals deleted
 Any time          One API retrieves the invoice
```

Details will be merged from the existing bundling and retrieval design.

## 11. Security

- Service accounts get only the storage and database access they need.
- Download links are signed and short-lived.
- Operations endpoints need a separate role.
- Logs contain IDs only, never payloads.
- Typst reads the payload as data, with file access limited to its assets directory.
- Secrets come from environment or a secret manager, never the repo.

## 12. Observability

- **Metrics:** step timing, Kafka lag, invoices by status, outbox age, counter wait time, Typst duration, replication slot lag.
- **Tracing:** one trace from intake to notification, carried in Kafka headers.
- **Logs:** structured, with `invoice_id`, `bu` and `source_event_id`.
- **Alerts:** oldest `NUMBERED` age, any `FAILED` invoice, outbox age, slot lag, DLQ growth.

## 13. Local environment

Docker Compose runs Kafka, PostgreSQL, Redis, `fake-gcs-server`, Mailpit, the Typst renderer, Prometheus, Grafana and, from M4, Kafka Connect with Debezium. Archive pricing can't be proven locally.

## 14. Rollout plan

| Phase | Outcome |
|---|---|
| 1 | Skeleton, schema, `subscriptions` through numbering |
| 2 | Polling outbox, render, storage, notification |
| 3 | Recovery cron, DLQ replay, failure tests |
| 4 | `enterprise`, then the remaining BUs |
| 5 | Load test with polling, then CDC and compare |
| 6 | Multiple series test |
| 7 | Archive and retrieval |
