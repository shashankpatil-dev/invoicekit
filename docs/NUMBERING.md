# Numbering Design

| | |
|---|---|
| Status | Design agreed, not built |
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
|   |   +-- shard AA  ->  SUBAA26000000001, ...02, ...03
|   |   +-- shard AB  ->  SUBAB26000000001, ...02, ...03
|   |
|   +-- FY 2027-28
|       +-- shard AA  ->  SUBAA27000000001, ...
|       +-- shard AB  ->  SUBAB27000000001, ...
|
+-- BU: enterprise  (1 shard)
    +-- FY 2026-27
        +-- shard AA  ->  ENTAA26000000001, ...

entity: ACME_KA (GSTIN 29AAAAA0000A1Z3)
+-- BU: subscriptions
    +-- FY 2026-27
        +-- shard AA  ->  SUBAA26000000001, ...
```

Rules:

- **Entity** is one GSTIN. Uniqueness is required per GSTIN, so `ACME_MH` and `ACME_KA` may both issue `SUBAA26000000001`. The state therefore doesn't need to appear in the number.
- **BU prefix** must be unique within an entity, because BUs share the GSTIN.
- **Financial year** runs from 1 April to 31 March, taken from `invoice_date`. Each year starts every series again at 1.
- **Shard** is one or two letters, `A` to `Z` or `AA` to `ZZ`. Each shard is a separate series, which Rule 46 allows ("one or multiple series").

## 4. Number format

Each BU's format lives in its adapter config. The format is a template of literals and tokens.

| Token | Output | Example |
|---|---|---|
| Literal text | Itself | `SUB` |
| `{shard}` | One shard letter, up to 26 shards | `A` |
| `{shard:2}` | Two shard letters, up to 676 shards | `AA` |
| `{fy}` | Both years of the financial year | `2627` |
| `{fy:start}` | Starting year of the financial year | `26` |
| `{fy:full}` | Full financial year | `2026-27` |
| `{seq:N}` | Sequence, zero-padded to N digits | `000000142` |

Chosen format: `SUB{shard:2}{fy:start}{seq:9}` gives `SUBAA26000000142`.

```text
 S U B   A A   2 6   0 0 0 0 0 0 1 4 2
 -----   ---   ---   -----------------
 prefix  shard FY    sequence, 9 digits
 3     + 2   + 2   + 9                    = 16 characters, the legal maximum
```

`SUB{shard:2}{fy:start}{seq:11}` would be 18 characters, which breaks the 16-character limit. Getting 11 digits needs 5 characters for everything else, such as a 1-letter prefix: `S{shard:2}{fy:start}{seq:11}`.

### Startup validation

The service refuses to start if any active format breaks these rules:

| Check | Why |
|---|---|
| Longest possible output is 16 characters or less | Rule 46 limit |
| Only letters, digits, `-` and `/` | Rule 46 characters |
| Exactly one `{seq:N}` | Every number needs a sequence |
| `{shard}` or `{shard:2}` present when shards is over 1 | Otherwise two shards produce the same number |
| Shard count fits the shard token: 26 for one letter, 676 for two | One code per shard |
| Prefix unique per entity across BUs | Otherwise two BUs collide under one GSTIN |

### Capacity

A series has two separate limits:

| Limit | What sets it | For `{seq:9}` |
|---|---|---|
| **Space** | Digits in `{seq:N}`, renewed every financial year | 999,999,999 numbers per series per year |
| **Speed** | How long the counter row is locked per number | Not measured yet. Expected in the hundreds per second |

Growth plan: one BU and entity at an average of 10 rps, growing 40% a year, with the chosen format:

| Year | Average rps | Invoices that year | Shards needed for space | Average rps per shard |
|---|---|---|---|---|
| 1 | 10 | 0.32B | 1 | 10 |
| 4 | 27 | 0.87B | 1 | 27 |
| 5 | 38 | 1.21B | 2 | 19 |
| 8 | 105 | 3.32B | 4 | 26 |
| 10 | 207 | 6.52B | 7 | 30 |
| 14 | 794 | 25.0B | 26 | 31 |
| 20 | 5,976 | 188B | 189 | 32 |
| 23 | 16,399 | 517B | 518 | 32 |
| **24** | **22,959** | **724B** | **725, over the 676 limit** | 32 |

- **This format lasts 23 years** at that growth before two shard letters run out.
- **Space, not speed, decides the shard count.** Each shard only averages about 30 rps, well below what one counter row should handle.
- **Peaks matter for speed.** In years 1 to 4, one series takes all traffic, including bursts like 100 rps. The load test must confirm one counter row handles the expected peak, and adding shards earlier is the fix if it doesn't.

Running out must never silently wrap or reuse numbers:

- The allocation SQL only increments while `last_no` is below the maximum, so it can't produce a 10-digit sequence.
- An alert fires when any series passes 80% of its yearly capacity.
- The fix is to add shards in config. New events spread into the new series.

## 5. Choosing a shard

The shard comes from the event ID:

```text
shard = code( hash(source_event_id) mod shards )

evt-101  -> hash -> 7  mod 2 = 1 -> AB
evt-102  -> hash -> 4  mod 2 = 0 -> AA
evt-103  -> hash -> 11 mod 2 = 1 -> AB
```

- **Even spread:** events split evenly across shards, so their counters share the load.
- **Deterministic:** the same event always maps to the same shard, which keeps tests and investigations simple.
- **Safe to change:** a duplicate of an already numbered event is caught by the invoice's unique key before a shard is chosen, so changing the shard count never renumbers anything.
- **Increasing shards:** new series start at 1 and existing ones continue.
- **Decreasing shards:** a removed shard's series stops. Its numbers stay contiguous, so it has no gap.
- **Who changes it:** an operator, in config, after an alert. Each new series appears in GST reporting, so it's never automatic.

Shard count is set per BU and entity, so a BU can have more shards in a busy state only.

## 6. The allocation SQL

PostgreSQL does the hard work, so the SQL is written to be correct under every race and to hold the counter lock for the shortest time.

### Order of work

```text
 Worker                                   PostgreSQL
   |                                          |
   | BEGIN  (READ COMMITTED)                  |
   | SET LOCAL lock_timeout, statement_timeout|
   |----------------------------------------->|
   |                                          |
   | 1. INSERT invoice, no number yet         |
   |    ON CONFLICT (bu, source_event_id)     |
   |    DO NOTHING                            |
   |----------------------------------------->|
   |                 0 rows: duplicate.       |
   |                 ROLLBACK and stop.       |
   |                 Counter never touched.   |
   |                                          |
   | 2. INSERT invoice_audit                  |
   |----------------------------------------->|
   |                                          |
   | 3. ONE statement:                        |
   |    increment counter (lock taken)        |
   |    set number on invoice                 |
   |    insert outbox row                     |
   |----------------------------------------->|
   |                 0 rows: series is full.  |
   |                 ROLLBACK, raise alert.   |
   |                                          |
   | COMMIT                                   |
   |----------------------------------------->|
   |                 lock released            |
```

The counter is the last thing touched, and everything after it happens in the same statement. So the lock is held for one statement plus the commit, with no extra network round trips while other workers wait.

### Statements

```sql
BEGIN;

SET LOCAL lock_timeout = '2s';
SET LOCAL statement_timeout = '5s';

INSERT INTO invoice (id, bu, entity_code, source_event_id, financial_year,
                     invoice_date, contract_version, payload, status)
VALUES (:invoiceId, :bu, :entityCode, :sourceEventId, :financialYear,
        :invoiceDate, :contractVersion, :payload, 'NUMBERED')
ON CONFLICT (bu, source_event_id) DO NOTHING
RETURNING id;

INSERT INTO invoice_audit (invoice_id, to_status, actor)
VALUES (:invoiceId, 'NUMBERED', 'intake');

WITH counter AS (
  INSERT INTO invoice_counter AS c (entity_code, bu, financial_year, shard, last_no)
  VALUES (:entityCode, :bu, :financialYear, :shard, 1)
  ON CONFLICT (entity_code, bu, financial_year, shard)
  DO UPDATE SET last_no = c.last_no + 1
  WHERE c.last_no < :maxSequence
  RETURNING last_no
),
numbered AS (
  UPDATE invoice AS i
  SET shard          = :shard,
      sequence_no    = counter.last_no,
      invoice_number = :prefix || lpad(counter.last_no::text, :sequenceWidth, '0')
  FROM counter
  WHERE i.id = :invoiceId
  RETURNING i.id, i.invoice_number
)
INSERT INTO outbox (id, invoice_id, topic, message_key, body)
SELECT :outboxId, numbered.id, 'invoice.render', numbered.id::text,
       jsonb_set(:renderMessage, '{invoiceNumber}', to_jsonb(numbered.invoice_number))
FROM numbered
RETURNING body ->> 'invoiceNumber' AS invoice_number;

COMMIT;
```

- `:prefix` is everything before the sequence, built by the format code, such as `SUBAA26`.
- `:maxSequence` is `10^width - 1`, such as 999,999,999.
- The saved invoice payload gets its `invoiceNumber` the same way, in the `numbered` step.

### Why each part is there

| Part | Case it handles |
|---|---|
| `READ COMMITTED` | PostgreSQL's default. `ON CONFLICT DO UPDATE` guarantees an insert or an update even under high concurrency, and always updates the latest committed counter value. Stricter levels would add retries without adding safety |
| Invoice insert first, `ON CONFLICT DO NOTHING` | A duplicate event stops before the counter. A second worker with the same event waits for the first to commit, then inserts 0 rows |
| Counter upsert | The first invoice of a new series creates the row with 1. Two workers creating it at once are handled by the upsert |
| `WHERE c.last_no < :maxSequence` | A full series isn't incremented. PostgreSQL still locks the row but returns nothing, so the statement returns 0 rows and the code raises `SeriesExhaustedException`. Without it, `lpad` would cut a 10-digit number down to 9 and repeat numbers |
| Counter, number and outbox in one statement | Shortest lock time. The number is written once and copied into the outbox, so they can't disagree |
| `SET LOCAL lock_timeout` | A worker never waits forever for a stuck counter. It fails, rolls back, and Kafka redelivers |
| `SET LOCAL statement_timeout` | A slow statement can't hold locks indefinitely |
| One counter row per transaction | Every transaction takes locks in the same order: invoice key, then counter. So they can't deadlock |
| Required caller transaction | The numbering service runs only inside an existing transaction (Spring `MANDATORY`), so a number can never commit alone |

### Database settings

| Setting | Value | Why |
|---|---|---|
| `idle_in_transaction_session_timeout` on the app role | `30s` | A worker that freezes mid-transaction, for example in a long garbage collection pause, is cut off and its locks released |
| Connection pool size | Bounded, set from load tests | A flood can't open more connections than the database can serve |
| Synchronous replication in HA setups | On | A failover can't lose a committed number. See section 8 |
| `transaction_timeout` | Not used yet | Needs PostgreSQL 17. The local stack runs 16.4 |

Per the PostgreSQL docs, `lock_timeout` and `statement_timeout` are set per transaction or per role, not in `postgresql.conf`, because a global value would affect every session.

## 7. Race scenarios

### Two different events, same series

```text
time --------------------------------------------------------------->

Worker A  INSERT ok   counter: 141 -> 142   COMMIT
                            |                 |
                            | lock held       | lock released
                            v                 v
Worker B  INSERT ok   counter: waits .........  142 -> 143   COMMIT

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
Worker A  shard AA counter: 141 -> 142   COMMIT
Worker B  shard AB counter:  88 ->  89   COMMIT     (no waiting)

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
| Failover to a replica that missed the last commit | The invoice and its number disappear together | Series stays consistent, but a number may already have reached Kafka and be reused. **Synchronous replication prevents this** |
| Series full | Allocation returns 0 rows and rolls back. Alert fires | No number issued. Fixed by adding shards |

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
    long next(SeriesKey series);
}
```

| Strategy | How | Order | Status |
|---|---|---|---|
| **Counter lock** | One row per series, locked by the increment | Strict | Default |
| Pool with `SKIP LOCKED` | Pre-created numbers, each worker claims the lowest free one | Can be out of order after a rollback | Comparison only |
| Batch allocation | One consumer per series numbers N events in one transaction | Strict | Future, only if one series needs it |

### Counter lock

```text
              invoice_counter
           +--------------------+
Worker 1 ->|  ACME_MH | SUB     |
Worker 2 ->|  2026-27 | AA      |   one row, one writer at a time
Worker 3 ->|  last_no = 142     |
           +--------------------+
```

Throughput per series is limited by how fast one transaction commits. Shards multiply it.

### Pool with `SKIP LOCKED`

```text
            number_pool for ACME_MH / SUB / 2026-27 / AA
           +-----+-----+-----+-----+-----+-----+
           | 142 | 143 | 144 | 145 | 146 | ... |
           +-----+-----+-----+-----+-----+-----+
Worker 1 ----^     |     |
Worker 2 ----------^     |      each worker skips rows others hold
Worker 3 ----------------^

Refill job: when free numbers drop below a threshold, add the next block.
```

It has no single busy row, and a rollback releases the number back to the pool. The catch is ordering: if the worker holding 142 rolls back after 143 commits, 142 goes to a later invoice. It also adds a refill job and more writes. It's kept only to measure against the counter lock, and isn't used in production unless out-of-order numbers are confirmed acceptable.

## 10. Scaling path

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
| Speed: too many writers on one series | `numbering_commit_duration_seconds`, and PostgreSQL lock waits in `pg_stat_activity` | p95 above 20ms |
| Space: the series is filling up | `numbering_series_utilisation` | Above 0.8 |
| Backlog: work arriving faster than it's done | Kafka consumer lag on `invoice.requested` | Still growing after 5 minutes |

## 11. Industry comparison

| System | Approach | Gaps | Order | Lesson for us |
|---|---|---|---|---|
| PostgreSQL community | Counter table with a row lock, because sequences are never rolled back ([mailing list](https://postgrespro.com/list/thread-id/1436372), [AppMaster](https://appmaster.io/blog/concurrency-safe-invoice-numbering), [Elixir Forum](https://forum.elixirforum.com/t/how-to-ensure-a-gapless-auto-incremental-id-field-when-using-postgresql/26340)) | None | Strict | Same core as ours. The usual warning is lock contention, which we handle with a short lock window and shards |
| SAP | Number ranges. Buffering gives speed but leaves gaps, so gapless billing turns buffering off and accepts lock waits on the range table `NRIV` ([SAP blog](https://blogs.sap.com/t5/technology-blogs-by-sap/buffering-number-ranges-and-legal-framework/ba-p/12953573), [SAP Insider](https://sapinsider.org/?p=14795), [SAP Q&A](https://answers.sap.com/questions/11347033/continuous-number-assignment-in-billing.html)) | None when unbuffered | Strict | Confirms that preallocated blocks break gap-free numbering. SAP also notes the rules differ by country, such as gapless in Germany but only ascending in Israel |
| Microsoft Dynamics 365 | Continuous sequences never skip, but a cancelled number is recycled later. Preallocation keeps continuous blocks in the database ([Microsoft Learn](https://learn.microsoft.com/en-us/dynamics365/fin-ops-core/fin-ops/organization-administration/number-sequence-overview), [2023 improvement](https://learn.microsoft.com/en-us/dynamics365/release-plan/2023wave1/finance-operations/finance-operations-crossapp-capabilities/enable-continuous-number-sequence-performance-improvements)) | None | Not strict: recycled numbers come out of order | This matches our `SKIP LOCKED` pool. Microsoft advises continuous sequences only where the law requires them, such as invoices |
| Odoo | "No gap" sequences lock the sequence row. Long transactions, like 100 invoices plus external signing in one commit, caused lock waits, so a community module switched to standard sequences that allow gaps ([OCA module](https://pypi.org/project/odoo12-addon-ir-sequence-standard-default/)) | Gaps when switched | Strict | The lock itself was fine. Holding it during slow work was the problem, which is why our lock is taken last and never held across a network call |

How our design compares:

- **Same foundation:** a counter row inside the business transaction, which is the standard gapless approach.
- **Stronger on contention:** the lock is taken last and released in one statement, shards split busy BUs, and timeouts cut off stuck sessions.
- **Rejects the speed shortcuts:** SAP-style buffering and Dynamics-style recycling trade away gaps or ordering. Our pool strategy is kept only for measurement.
- **Matches the PostgreSQL guarantee:** the docs state `ON CONFLICT DO UPDATE` guarantees an atomic insert or update "even under high concurrency", and that a row skipped by the `WHERE` condition "will still be locked" but not returned ([INSERT docs](https://www.postgresql.org/docs/current/sql-insert.html), [isolation docs](https://www.postgresql.org/docs/current/transaction-iso.html), [timeout settings](https://www.postgresql.org/docs/current/runtime-config-client.html)).

One caution from the PostgreSQL docs: `INSERT ... ON CONFLICT` can fail with a unique violation while `CREATE INDEX CONCURRENTLY` or `REINDEX CONCURRENTLY` runs on the same unique index. Index maintenance on `invoice` and `invoice_counter` should run in a quiet window.

## 12. Data model changes

These change the current schema when numbering is built.

| Table | Change |
|---|---|
| `bu_entity` | `number_shards int NOT NULL DEFAULT 1 CHECK (number_shards BETWEEN 1 AND 676)` |
| `invoice` | `shard varchar(2)`. Unique on `(entity_code, bu, financial_year, shard, sequence_no)`. `UNIQUE (entity_code, financial_year, invoice_number)` stays as the final safety net |
| `invoice_counter` | `shard varchar(2) NOT NULL`. Primary key becomes `(entity_code, bu, financial_year, shard)` |
| `adapter_config` | `number_format` uses the tokens in section 4 |

The project has no production data yet, so these go straight into the `V1` migration instead of an `ALTER`.

## 13. Code structure

```text
numbering/
  domain/entity/
    SeriesKey            entity, BU, financial year, shard
    FinancialYear        built from a date, April to March
    NumberFormat         parses and validates the template, builds the prefix
    ShardCode            shard index to letters, such as 0 -> AA
    InvoiceNumber        series, sequence number and formatted value
  domain/exception/
    SeriesExhaustedException
  application/service/
    NumberingService     picks the shard, allocates, formats
  application/port/outbound/
    SequenceCounter
  adapter/outbound/postgres/
    CounterLockSequenceCounter
```

```java
public record NumberingRequest(
    String entityCode,
    String businessUnit,
    String sourceEventId,
    LocalDate invoiceDate,
    NumberFormat format,
    int shards) {}

public record InvoiceNumber(SeriesKey series, long sequenceNo, String value) {}
```

`NumberingService.assign(request)` returns an `InvoiceNumber`. The invoice module calls it inside its own transaction, and the domain classes have no Spring or database code.

## 14. Testing

| Test | Proves |
|---|---|
| Format unit tests | Tokens, padding, 16-character limit, allowed characters |
| Financial year unit tests | 31 March and 1 April land in different years |
| Shard unit tests | Same event, same shard. Even spread. Index 0 is `AA`, 675 is `ZZ` |
| Capacity test | A series at its maximum returns 0 rows and raises, instead of wrapping |
| Concurrency test | 32 threads allocate in parallel. Every series is exactly 1 to N |
| Duplicate test | The same event from many threads gets one number |
| Rollback test | A rolled-back transaction leaves no gap |
| Lock timeout test | A held counter row makes the next worker fail after `lock_timeout`, not hang |
| Strategy load test | Counter lock against pool, 1 shard against several, on one busy series |

Reconciliation query, run after every concurrency and failure test:

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

## 15. Observability

| Metric | Use |
|---|---|
| `numbering_commit_duration_seconds{bu,entity}` | Time the counter row is locked |
| `numbering_allocations_total{bu,entity,shard}` | Spread across shards |
| `numbering_series_utilisation{bu,entity,shard}` | `last_no` against capacity, alert at 0.8 |
| `numbering_exhausted_total` | Must stay at 0 |

## 16. Open questions

- Each shard is a separate series in GST returns, as I understand it. Confirm how the returns list multiple series per GSTIN.
- Confirm how to number a late invoice dated in the previous financial year. The design continues that year's series.
- Confirm that a 2-digit starting year, such as `26` for 2026-27, is clear enough for auditors.
- Measure one counter row's maximum rate, which decides whether early years need shards for speed.
