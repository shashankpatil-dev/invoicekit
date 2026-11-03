# Numbering Design

| | |
|---|---|
| Status | Built and load tested |
| Date | 2026-11-04 |
| Related | [LLD](LLD.md), [HLD](HLD.md), [Invoice Superset](INVOICE_SUPERSET.md) |

Numbering gives every invoice a unique, gap-free number within its series. This document covers what it guarantees, how a number is built, the SQL that allocates it, how races and failures are handled, how it scales, and how it compares with established systems.

## 1. Guarantees

| Guarantee | Meaning |
|---|---|
| Unique | No two invoices share a number for one GSTIN in one financial year |
| Gap-free | Rollbacks, crashes and duplicate events never skip a number |
| Ordered | Within one series, a higher number was always committed later |
| Atomic | The number commits together with the invoice, or not at all |
| Scalable | A busy BU can be split into more series without code changes |

## 2. Where numbering sits

Numbering is a common module. It runs inside the intake worker, in the same PostgreSQL transaction that inserts the invoice. PostgreSQL is its only dependency. It never calls Kafka, Redis or any other service.

```text
                    +-------------------------+
                    | Kafka                   |
                    | invoice.requested       |
                    +-------------------------+
                                |
                                v
+------------------------------------------------------------------+
| INTAKE WORKER (one JVM process)                                  |
|                                                                  |
|  +-------------+    +-------------+    +---------------------+   |
|  | Kafka       |--->| BU adapter  |--->| Invoice service     |   |
|  | listener    |    | payload,    |    | opens the           |   |
|  |             |    | tax         |    | transaction         |   |
|  +-------------+    +-------------+    +---------------------+   |
|                                                   |              |
|                                                   | same         |
|                                                   | transaction  |
|                                                   v              |
|                                       +---------------------+    |
|                                       | NUMBERING MODULE    |    |
|                                       | series, shard,      |    |
|                                       | counter, format     |    |
|                                       +---------------------+    |
+------------------------------------------------------------------+
                                                    |
                                                    v
                    +------------------------------------------+
                    | PostgreSQL                               |
                    | invoice, invoice_counter, outbox         |
                    +------------------------------------------+

  Redis    not used for numbers
  Kafka    not used for numbers
```

## 3. The series model

A series is one independent run of numbers starting at 1. Each series has its own counter row, so different series never wait on each other.

```text
Series key = entity + BU + financial year + shard

entity: ACME_MH (GSTIN 27AAAAA0000A1Z5)
|
+-- BU: subscriptions
|   |
|   +-- FY 2026-27
|   |   +-- shard A  ->  SUBA260000000001, ...02, ...03
|   |   +-- shard B  ->  SUBB260000000001, ...02, ...03
|   |
|   +-- FY 2027-28
|       +-- shard A  ->  SUBA270000000001, ...
|       +-- shard B  ->  SUBB270000000001, ...
|
+-- BU: enterprise  (1 shard)
    +-- FY 2026-27
        +-- shard A  ->  ENTA260000000001, ...

entity: ACME_KA (GSTIN 29AAAAA0000A1Z3)
+-- BU: subscriptions
    +-- FY 2026-27
        +-- shard A  ->  SUBA260000000001, ...
```

Rules:

- **Entity** is one GSTIN. Uniqueness is required per GSTIN, so `ACME_MH` and `ACME_KA` may both issue `SUBA260000000001`. The state therefore doesn't need to appear in the number.
- **BU prefix** must be unique within an entity, because BUs share the GSTIN.
- **Financial year** runs from 1 April to 31 March, taken from `invoice_date`. It is stored as `2026-27`, and each year starts every series again at 1.
- **Shard** is one letter, `A` to `Z`. Each shard is a separate series, which Rule 46 allows ("one or multiple series").

## 4. Number format

Each BU's format lives in its adapter config. The format is a template of literals and tokens.

| Token | Output | Example |
|---|---|---|
| Literal text | Itself | `SUB` |
| `{shard}` | Shard letter, up to 26 shards | `A` |
| `{fy}` | Two-digit starting year of the financial year | `26` for 2026-27 |
| `{seq:N}` | Sequence, zero-padded to N digits | `0000000142` |

Chosen format: `SUB{shard}{fy}{seq:10}` gives `SUBA260000000142`.

```text
 S U B   A   2 6   0 0 0 0 0 0 0 1 4 2
 -----   -   ---   -------------------
 prefix  |   FY    sequence, 10 digits
         shard
 3     + 1 + 2   + 10                    = 16 characters, the legal maximum
```

### Validation

`NumberFormat.parse` rejects a template, with the reason, if it breaks any rule:

| Check | Why |
|---|---|
| Longest output is 16 characters or less | Rule 46 limit |
| Only letters, digits, `-` and `/` | Rule 46 characters |
| Exactly one `{seq:N}`, with N from 1 to 16 | Every number needs a sequence |
| `{shard}` and `{fy}` at most once each | A repeated token is a mistake |

Other checks:

- `NumberingRule` refuses more than 1 shard unless the format uses `{shard}`, because otherwise two shards would produce the same numbers.
- `NumberFormat.format` refuses a sequence that doesn't fit, so a number is never cut short.
- `NumberFormat.leadingLiteral()` gives the prefix, such as `SUB`. The adapter registry, when built, will use it at startup to check that two BUs under one entity don't share a prefix.

### Capacity

A series has two separate limits:

| Limit | What sets it | For `{seq:10}` |
|---|---|---|
| **Space** | Digits in `{seq:N}`, renewed every financial year | 9,999,999,999 numbers per series per year |
| **Speed** | How long the counter row is locked per number | About 880 numbers/sec on one series in the Kubernetes load test, see section 10 |

Growth plan: one BU and entity growing from 10 lakh to 1 crore orders a day over 10 years, about 29% a year:

| Year | Orders a day | Invoices that year | Average rps | Shards needed for space |
|---|---|---|---|---|
| 1 | 1.0M | 0.37B | 12 | 1 |
| 5 | 2.8M | 1.0B | 32 | 1 |
| 10 | 10.0M | 3.65B | 116 | 1 |
| 15 | 35.9M | 13.1B | 416 | 2 |
| 20 | 129M | 47.1B | 1,495 | 5 |

- **One series has enough space for the whole 10-year plan.** 26 shards hold about 260B numbers a year, which lasts far beyond it.
- **Speed decides when to add shards.** At year 10 the average is 116 rps, and peaks may be several times that. One series held 600 rps at p99 4.2ms in the load test, so add shards before a series' peak reaches about 600 rps.

Running out must never silently wrap or reuse numbers:

- The allocation SQL only increments while `last_no` is below the maximum, and `NumberFormat.format` refuses a sequence that doesn't fit.
- `numbering.series.utilisation` tracks how full each series is, and a warning is logged the first time a series passes 80%.
- The fix is to add shards in config. New events spread into the new series.

## 5. Choosing a shard

The shard comes from the event ID:

```text
shard = letter( floorMod(sourceEventId.hashCode(), shards) )

evt-101  -> hash mod 2 = 1 -> B
evt-102  -> hash mod 2 = 0 -> A
evt-103  -> hash mod 2 = 1 -> B
```

- **Even spread:** events split evenly across shards, so their counters share the load.
- **Deterministic:** `String.hashCode` is fixed by the Java language, so the same event always maps to the same shard on every JVM.
- **Safe to change:** a duplicate of an already numbered event is caught by the invoice's unique key before a shard is chosen, so changing the shard count never renumbers anything.
- **Increasing shards:** new series start at 1 and existing ones continue.
- **Decreasing shards:** a removed shard's series stops. Its numbers stay contiguous, so it has no gap.
- **Who changes it:** an operator, in `bu_entity.number_shards`, after a warning or a load test. Each new series appears in GST reporting, so it's never automatic.

## 6. The allocation transaction

Numbering owns one statement, the counter increment. The invoice module owns the statements around it. Keeping that boundary costs one extra statement while the counter is locked, which is small next to keeping the modules separate.

### Order of work

```text
 Invoice module                Numbering module              PostgreSQL
      |                              |                            |
      | BEGIN (READ COMMITTED)       |                            |
      |---------------------------------------------------------->|
      | 1. INSERT invoice, no number |                            |
      |    ON CONFLICT (bu, source_event_id) DO NOTHING           |
      |---------------------------------------------------------->|
      |                0 rows: duplicate. ROLLBACK and stop.      |
      |                The counter is never touched.              |
      |                              |                            |
      | 2. assign(request) --------->|                            |
      |                              | SET LOCAL lock_timeout,    |
      |                              | statement_timeout          |
      |                              | upsert counter (lock taken)|
      |                              |--------------------------->|
      |                              |     0 rows: series full    |
      |<----- InvoiceNumber ---------|                            |
      |                              |                            |
      | 3. UPDATE invoice: shard, sequence, number                |
      |    and INSERT outbox, in one statement                    |
      |---------------------------------------------------------->|
      | COMMIT                                      lock released |
      |---------------------------------------------------------->|
```

### The counter statement

`CounterLockSequenceCounter` runs these, inside the caller's transaction:

```sql
SET LOCAL lock_timeout = '2000ms';
SET LOCAL statement_timeout = '5000ms';

INSERT INTO invoice_counter AS c (entity_code, bu, financial_year, shard, last_no)
VALUES (:entityCode, :businessUnit, :financialYear, :shard, 1)
ON CONFLICT (entity_code, bu, financial_year, shard)
DO UPDATE SET last_no = c.last_no + 1
WHERE c.last_no < :maxSequence
RETURNING last_no;
```

### Why each part is there

| Part | Case it handles |
|---|---|
| `READ COMMITTED` | PostgreSQL's default. `ON CONFLICT DO UPDATE` guarantees an insert or an update even under high concurrency, and always updates the latest committed counter value. Stricter levels would add retries without adding safety |
| Invoice insert first, `ON CONFLICT DO NOTHING` | A duplicate event stops before the counter. A second worker with the same event waits for the first to commit, then inserts 0 rows |
| Counter upsert | The first invoice of a new series creates the row with 1. Two workers creating it at once are handled by the upsert |
| `WHERE c.last_no < :maxSequence` | A full series isn't incremented. PostgreSQL still locks the row but returns nothing, and the adapter throws `SeriesExhaustedException` |
| Counter touched late, then one statement for the number and outbox | The lock is held for two statements plus the commit |
| `SET LOCAL lock_timeout` | A worker never waits forever for a stuck counter. It fails with `SeriesBusyException`, rolls back, and Kafka redelivers |
| `SET LOCAL statement_timeout` | A slow statement can't hold locks indefinitely |
| One counter row per transaction | Every transaction locks the invoice key and then one counter row, always in that order, so they can't deadlock |
| `@Transactional(propagation = MANDATORY)` on `NumberingService.assign` | Numbering refuses to run without the caller's transaction, so a number can never commit alone |

### Detecting timeouts

Spring reports PostgreSQL's errors inconsistently: a lock timeout (SQL state `55P03`) arrives as an uncategorised error, and a statement timeout (`57014`) as `QueryTimeoutException`. The adapter therefore checks the SQL state itself, turns both into `SeriesBusyException`, and lets every other database error through unchanged.

### Database settings

| Setting | Where | Value | Why |
|---|---|---|---|
| `lock_timeout` | `numbering.properties` | `2s` | Longest wait for a busy counter |
| `statement_timeout` | `numbering.properties` | `5s` | Longest statement in a numbering transaction |
| `idle_in_transaction_session_timeout` | `database.properties`, set on every pooled connection | `30s` | A worker that freezes mid-transaction is cut off and its locks released |
| `fillfactor` on `invoice_counter` | Schema | `50` | Counter rows are updated constantly. Free space lets PostgreSQL keep new row versions in the same page (HOT updates), so the table doesn't bloat |
| Connection pool size | Spring settings | Set from load tests | A flood can't open more connections than the database can serve, and a measured cap per series keeps lock queues short |

`NumberingProperties` refuses timeouts below 1ms, because PostgreSQL treats `0` as no timeout at all. Per the PostgreSQL docs, these timeouts belong per transaction or per role, not in `postgresql.conf`, because a global value affects every session.

### Schema constraints

The database enforces the rules as well, as the last line of defence:

| Constraint | Rule |
|---|---|
| `invoice_series_sequence_unique` | One sequence per series |
| `invoice_number_unique` | One number per GSTIN per financial year |
| `invoice_number_format` | At most 16 characters, only letters, digits, `-` and `/` |
| `invoice_shard_format` | Shard is one capital letter |
| `invoice_sequence_positive` | Sequence starts at 1 |
| `invoice_financial_year_format` | Year looks like `2026-27` |
| `invoice_numbered_together` | Shard, sequence and number are all set or all empty |
| `bu_entity_number_shards_range` | 1 to 26 shards |

## 7. Race scenarios

### Two different events, same series

```text
time --------------------------------------------------------------->

Worker A  INSERT ok   counter: 141 -> 142   update + outbox   COMMIT
                            |                                   |
                            | lock held                         | released
                            v                                   v
Worker B  INSERT ok   counter: waits ............................  142 -> 143

Result: A = 142, B = 143. Unique, no gap, in commit order.
```

### The same event delivered twice

```text
Worker A  INSERT ok (evt-101)   counter +1   COMMIT
                  |                             |
                  | unique key held             | released
                  v                             v
Worker B  INSERT (evt-101) waits ................  0 rows -> ROLLBACK

Result: one invoice. Worker B never touched the counter.
```

### Two events in different shards

```text
Worker A  shard A counter: 141 -> 142   COMMIT
Worker B  shard B counter:  88 ->  89   COMMIT     (no waiting)

Result: both run in parallel. This is how shards add throughput.
```

## 8. Failure scenarios

PostgreSQL writes every change to its write-ahead log (WAL) and only treats it as real after `COMMIT`. The counter is an ordinary row, so it's undone like any other change. A PostgreSQL `SEQUENCE` is never undone, which is why it isn't used.

| Failure | What happens | Effect on the series |
|---|---|---|
| Code error before commit | Spring rolls back | Counter returns to its old value |
| Worker crash or network drop before commit | PostgreSQL sees the closed connection and aborts | Same |
| Database crash before commit | Recovery replays only committed work | Same |
| Commit succeeded, but the reply was lost | Worker treats it as a failure and Kafka redelivers. Step 1 finds the invoice and stops | One number, never two |
| Commit succeeded, worker crashed before the Kafka ack | Kafka redelivers. Step 1 finds the invoice and stops | Same |
| Failover in Cloud SQL high availability | Google documents that writes reach both zones' disks "before a transaction is reported as committed" ([Cloud SQL HA](https://docs.cloud.google.com/sql/docs/postgres/high-availability)) | A committed number survives the failover |
| Series full | Counter returns 0 rows, `SeriesExhaustedException`, rollback | No number issued. Fixed by adding shards |

### Wider system failures

| Situation | What happens | Result |
|---|---|---|
| Database down | Intake can't commit, retries with backoff, and pauses its Kafka partitions | No numbers issued. Events wait in Kafka for up to 7 days |
| Worker frozen holding the lock | `lock_timeout` stops waiters, and `idle_in_transaction_session_timeout` ends the frozen session | Its transaction rolls back, and others continue |
| Kafka lag | Events wait longer | No correctness issue. The year comes from `invoice_date`, so an event dated 31 March but processed on 1 April still uses the old year's series |
| Event flood | Kafka holds the backlog, while consumers and the connection pool stay at a fixed size | The database is never overwhelmed. A 100 rps burst for 10 minutes is at most 60,000 events |
| One BU floods | Its events share partitions with other BUs | Others slow down. Per-BU topics or quotas would fix this if needed |
| Duplicate flood | Each duplicate stops at the unique key | Cheap, and the counter is never touched |
| Redis down | Numbering doesn't use Redis | No effect |

## 9. Allocation strategies

All strategies sit behind one port, so they can be swapped and compared.

```java
public interface SequenceCounter {
    long next(SeriesKey series, long maxSequence);
}
```

| Strategy | How | Order | Status |
|---|---|---|---|
| **Counter lock** | One row per series, locked by the increment | Strict | Built: `CounterLockSequenceCounter` |
| Pool with `SKIP LOCKED` | Pre-created numbers, each worker claims the lowest free one | Can be out of order after a rollback | Comparison only, not built |
| Batch allocation | One consumer per series numbers N events in one transaction | Strict | Future, only if one series needs it |

### Counter lock

```text
              invoice_counter
           +--------------------+
Worker 1 ->|  ACME_MH | SUB     |
Worker 2 ->|  2026-27 | A       |   one row, one writer at a time
Worker 3 ->|  last_no = 142     |
           +--------------------+
```

### Pool with `SKIP LOCKED`

```text
            number_pool for ACME_MH / SUB / 2026-27 / A
           +-----+-----+-----+-----+-----+-----+
           | 142 | 143 | 144 | 145 | 146 | ... |
           +-----+-----+-----+-----+-----+-----+
Worker 1 ----^     |     |
Worker 2 ----------^     |      each worker skips rows others hold
Worker 3 ----------------^

Refill job: when free numbers drop below a threshold, add the next block.
```

It has no single busy row, and a rollback releases the number back to the pool. The catch is ordering: if the worker holding 142 rolls back after 143 commits, 142 goes to a later invoice. It also adds a refill job and more writes. It isn't used unless out-of-order numbers are confirmed acceptable.

## 10. Scaling and measured speed

### Measured speed of one counter row

Measured with `pgbench` on PostgreSQL 16.4 in Docker with 2 CPUs on one laptop. Each transaction inserts an invoice row and increments the counter, as the real flow does.

| Setup | Concurrent workers | Numbers per second | Average latency |
|---|---|---|---|
| 1 series | 8 | 827 | 9.7ms |
| 1 series | 32 | 420 | 76ms |
| 4 shards | 8 | 1,373 | 5.8ms |
| 4 shards | 32 | 874 | 37ms |

What this shows:

- **More workers on one series make it slower.** Going from 8 to 32 workers halves throughput, because waiters pile up behind the row lock. Concurrency per series must stay capped, and the connection pool and consumer threads are the cap.
- **Shards multiply throughput,** because each has its own row and its own queue.
- **These are local numbers.** A real database adds network time to each lock hold.

### Kubernetes load test

Run through the real numbering path in `kind`, with 2 app pods, a pool of 10 per pod, and PostgreSQL with 2 CPUs. Full results are in [Load Test Results](LOAD_TEST.md#numbering).

| Setup | Rate | Achieved | p99 | PostgreSQL cores |
|---|---|---|---|---|
| 1 series | 600 rps | 600 rps | 4.2ms | 0.44 |
| 1 series | 1,000 rps | 880 rps, saturated | 1,662ms | 1.87 |
| 4 shards | 1,000 rps | 1,000 rps | 2.9ms | 0.67 |

The saturated single series spent its extra CPU on workers queuing for the counter row, and 4 shards at the same rate used a third of the CPU. No phase lost or repeated a number.

### Scaling path

```text
 Load on one BU + entity
 ---------------------------------------------------------------------------->

 Step 1  Counter lock, 1 shard        simplest, strict order
 Step 2  Counter lock, N shards       config change only, throughput and space x N
 Step 3  Batch allocation             only if a single series must go faster
```

Each step keeps every guarantee in section 1.

### Spotting pressure

| Pressure | Signal | Alert |
|---|---|---|
| Speed: too many writers on one series | `numbering.allocation` timer, and PostgreSQL lock waits in `pg_stat_activity` | p95 above 20ms |
| Space: the series is filling up | `numbering.series.utilisation`, plus a warning log at 0.8 | Above 0.8 |
| Backlog: work arriving faster than it's done | Kafka consumer lag on `invoice.requested` | Still growing after 5 minutes |

## 11. Industry comparison

| System | Approach | Gaps | Order | Lesson for us |
|---|---|---|---|---|
| PostgreSQL community | Counter table with a row lock, because sequences are never rolled back ([mailing list](https://postgrespro.com/list/thread-id/1436372), [AppMaster](https://appmaster.io/blog/concurrency-safe-invoice-numbering), [Elixir Forum](https://forum.elixirforum.com/t/how-to-ensure-a-gapless-auto-incremental-id-field-when-using-postgresql/26340)) | None | Strict | Same core as ours. The usual warning is lock contention, which we handle with a short lock window, a concurrency cap and shards |
| SAP | Number ranges. Buffering gives speed but leaves gaps, so gapless billing turns buffering off and accepts lock waits on the range table `NRIV` ([SAP blog](https://blogs.sap.com/t5/technology-blogs-by-sap/buffering-number-ranges-and-legal-framework/ba-p/12953573), [SAP Insider](https://sapinsider.org/?p=14795), [SAP Q&A](https://answers.sap.com/questions/11347033/continuous-number-assignment-in-billing.html)) | None when unbuffered | Strict | Confirms that preallocated blocks break gap-free numbering. SAP also notes the rules differ by country, such as gapless in Germany but only ascending in Israel |
| Microsoft Dynamics 365 | Continuous sequences never skip, but a cancelled number is recycled later. Preallocation keeps continuous blocks in the database ([Microsoft Learn](https://learn.microsoft.com/en-us/dynamics365/fin-ops-core/fin-ops/organization-administration/number-sequence-overview), [2023 improvement](https://learn.microsoft.com/en-us/dynamics365/release-plan/2023wave1/finance-operations/finance-operations-crossapp-capabilities/enable-continuous-number-sequence-performance-improvements)) | None | Not strict: recycled numbers come out of order | This matches our `SKIP LOCKED` pool. Microsoft advises continuous sequences only where the law requires them, such as invoices |
| Odoo | "No gap" sequences lock the sequence row. Long transactions, like 100 invoices plus external signing in one commit, caused lock waits, so a community module switched to standard sequences that allow gaps ([OCA module](https://pypi.org/project/odoo12-addon-ir-sequence-standard-default/)) | Gaps when switched | Strict | The lock itself was fine. Holding it during slow work was the problem, which is why our lock is taken late and never held across a network call to another service |

How our design compares:

- **Same foundation:** a counter row inside the business transaction, which is the standard gapless approach.
- **Stronger on contention:** the lock is taken late and held briefly, shards split busy BUs, and timeouts cut off stuck sessions.
- **Rejects the speed shortcuts:** SAP-style buffering and Dynamics-style recycling trade away gaps or ordering.
- **Matches the PostgreSQL guarantee:** the docs state `ON CONFLICT DO UPDATE` guarantees an atomic insert or update "even under high concurrency", and that a row skipped by the `WHERE` condition "will still be locked" but not returned ([INSERT docs](https://www.postgresql.org/docs/current/sql-insert.html), [isolation docs](https://www.postgresql.org/docs/current/transaction-iso.html), [timeout settings](https://www.postgresql.org/docs/current/runtime-config-client.html)).

One caution from the PostgreSQL docs: `INSERT ... ON CONFLICT` can fail with a unique violation while `CREATE INDEX CONCURRENTLY` or `REINDEX CONCURRENTLY` runs on the same unique index. Index maintenance on `invoice` and `invoice_counter` should run in a quiet window.

## 12. Code structure

```text
numbering/
  domain/entity/
    FinancialYear          a date's financial year, April to March
    Shard                  shard index and letter, chosen from the event ID
    NumberFormat           a parsed template that formats numbers
    NumberFormatParser     reads and validates a template
    TemplatePart           sealed type: literal, {shard}, {fy}, {seq:N}, each renders itself
    NumberingRule          format plus shard count for one BU and entity
    SeriesKey              entity, BU, financial year, shard
    InvoiceNumber          series, sequence and formatted value
  domain/exception/
    InvalidNumberFormatException, SeriesExhaustedException, SeriesBusyException
  application/port/outbound/
    SequenceCounter        next number in a series
  application/service/
    NumberingRequest       what the invoice module asks for, and the series it maps to
    NumberingService       assign(request), only inside the caller's transaction
    NumberingMetrics       timer, counters, utilisation gauge and warning
  adapter/outbound/postgres/
    CounterLockSequenceCounter

bootstrap/configuration/
  NumberingConfiguration   wires the counter, metrics and service
  NumberingProperties      timeouts and warning level, from config/numbering.properties
```

Design rules, enforced by `NumberingArchitectureTest`:

- The domain has no Spring, JDBC, Micrometer or Jackson code.
- The application layer depends on the `SequenceCounter` port, never on the adapter.
- Numbering doesn't depend on other feature modules.

## 13. Testing

| Test | Proves |
|---|---|
| Domain unit tests | Tokens, padding, the 16-character limit, allowed characters, financial year boundaries, shard spread |
| `NumberingSchemaTest` | Every constraint rejects bad data in a real PostgreSQL |
| `CounterLockSequenceCounterTest` | Counting, independent series, exhaustion |
| `CounterLockSequenceCounterTransactionTest` | Rollback leaves no gap, and lock and statement timeouts become `SeriesBusyException` |
| `NumberingServiceTest`, `NumberingMetricsTest` | Series choice, formatting, metrics and the one-time warning |
| `DummyOrderNumberingTest` | 1 and 10 dummy orders on the temporary test series, through the real service and database |
| `NumberingConcurrencyTest` | Many threads at once: no gaps, no duplicates, one invoice per repeated event, no gap after a failed transaction |
| `NumberingFailureTest` | A connection killed before commit leaves its number for the next order. The database paused for 3 seconds under load still ends with one number per order. A full series refuses the next order and rolls it back |
| `NumberingFrozenWorkerTest` | A worker idle mid-transaction is cut off by `idle_in_transaction_session_timeout`, and a waiting worker takes its number |
| `NumberingBusySeriesRetryTest` | With a 2ms lock timeout, hundreds of `SeriesBusyException` retries still number every order exactly once |
| `NumberingSmallPoolTest` | 32 threads sharing 4 connections all succeed by waiting, with no retries |
| `NumberingEdgeCaseTest` | Redelivery after commit returns the original number. Changing 1 shard to 4 keeps old numbers and spreads new ones. Orders across 31 March and 1 April land in separate series. Independent series never affect each other |

The temporary test series lives in `src/test/resources/db/testseries/`. Delete that folder to discard it.

Reconciliation query, run after the concurrency tests:

```sql
SELECT entity_code, bu, financial_year, shard,
       count(*) AS issued, min(sequence_no) AS first, max(sequence_no) AS last
FROM invoice
WHERE sequence_no IS NOT NULL
GROUP BY entity_code, bu, financial_year, shard
HAVING count(*) <> max(sequence_no)
    OR min(sequence_no) <> 1
    OR count(DISTINCT sequence_no) <> count(*);
```

Zero rows means no gaps and no duplicates. A second check confirms that each series' `max(sequence_no)` equals its `invoice_counter.last_no`.

## 14. Observability

| Metric | Tags | Use |
|---|---|---|
| `numbering.allocation` (timer) | `businessUnit`, `entity` | Time spent getting a number, including lock waits |
| `numbering.allocations` (counter) | `businessUnit`, `entity`, `shard` | Spread across shards |
| `numbering.exhausted` (counter) | `businessUnit`, `entity` | Must stay at 0 |
| `numbering.series.utilisation` (gauge) | `businessUnit`, `entity`, `financialYear`, `shard` | How full a series is, from 0 to 1 |

Prometheus shows these with underscores, such as `numbering_allocation_seconds`.

## 15. Open questions

- Each shard is a separate series in GST returns, as I understand it. Confirm how the returns list multiple series per GSTIN.
- Confirm how to number a late invoice dated in the previous financial year. The design continues that year's series.
- Confirm that a 2-digit starting year, such as `26` for 2026-27, is clear enough for auditors.
- Load test the full flow through Kafka once the invoice module exists, including more app pods and other pool sizes.
