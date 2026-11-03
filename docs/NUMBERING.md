# Numbering Design

| | |
|---|---|
| Status | Design agreed, not built |
| Date | 2026-11-04 |
| Related | [LLD](LLD.md), [HLD](HLD.md), [Invoice Superset](INVOICE_SUPERSET.md) |

Numbering gives every invoice a unique, gap-free number within its series. This document covers what it guarantees, how a number is built, how races and crashes are handled, and how it scales.

## 1. Guarantees

| Guarantee | Meaning |
|---|---|
| Unique | No two invoices share a number for one GSTIN in one financial year |
| Gap-free | Rollbacks, crashes and duplicate events never skip a number |
| Ordered | Within one series, a higher number was always committed later |
| Atomic | The number commits together with the invoice, or not at all |
| Scalable | A busy BU can be split into more series without code changes |

## 2. Where numbering sits

Numbering is a common module. It runs inside the intake worker, in the same PostgreSQL transaction that inserts the invoice. It never calls Kafka, Redis or any other service.

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
|   |   +-- shard A  ->  SUBA262700000001, ...02, ...03
|   |   +-- shard B  ->  SUBB262700000001, ...02, ...03
|   |
|   +-- FY 2027-28
|       +-- shard A  ->  SUBA272800000001, ...
|       +-- shard B  ->  SUBB272800000001, ...
|
+-- BU: enterprise  (1 shard)
    +-- FY 2026-27
        +-- shard A  ->  ENT2627000000001, ...

entity: ACME_KA (GSTIN 29AAAAA0000A1Z3)
+-- BU: subscriptions
    +-- FY 2026-27
        +-- shard A  ->  SUBA262700000001, ...
```

Rules:

- **Entity** is one GSTIN. Uniqueness is required per GSTIN, so `ACME_MH` and `ACME_KA` may both issue `SUBA262700000001`. The state therefore doesn't need to appear in the number.
- **BU prefix** must be unique within an entity, because BUs share the GSTIN.
- **Financial year** runs from 1 April to 31 March, taken from `invoice_date`. Each year starts every series again at 1.
- **Shard** is a letter, `A` to `Z`. Each shard is a separate series, which Rule 46 allows ("one or multiple series").

## 4. Number format

Each BU's format lives in its adapter config. The format is a template of literals and tokens.

| Token | Output | Example |
|---|---|---|
| Literal text | Itself | `SUB` |
| `{shard}` | Shard letter | `A` |
| `{fy}` | Short financial year | `2627` |
| `{fy:full}` | Full financial year | `2026-27` |
| `{seq:N}` | Sequence, zero-padded to N digits | `00000142` |

Example: `SUB{shard}{fy}{seq:8}` gives `SUBA262700000142`.

```text
 S U B   A   2 6 2 7   0 0 0 0 0 1 4 2
 -----   -   -------   ---------------
 prefix  |   FY        sequence, 8 digits
         shard
 3     + 1 + 4       + 8                 = 16 characters, the legal maximum
```

### Startup validation

The service refuses to start if any active format breaks these rules:

| Check | Why |
|---|---|
| Longest possible output is 16 characters or less | Rule 46 limit |
| Only letters, digits, `-` and `/` | Rule 46 characters |
| Exactly one `{seq:N}` | Every number needs a sequence |
| `{shard}` present when shards is over 1 | Otherwise two shards produce the same number |
| Prefix unique per entity across BUs | Otherwise two BUs collide under one GSTIN |
| Shard count between 1 and 26 | One letter per shard |

### Capacity per series

The sequence width sets how many invoices one series can hold in a year.

| `{seq:N}` | Numbers per series per year | Sustained rate one series can take for a year |
|---|---|---|
| 6 | 999,999 | About 0.03 rps |
| 7 | 9,999,999 | About 0.3 rps |
| 8 | 99,999,999 | About 3.2 rps |

At the 20 rps average, the whole system issues about 630 million invoices a year. So a busy BU needs a wide sequence and several shards. With `{seq:8}`, 10 shards give about 1 billion numbers a year for one BU and entity.

Running out must never silently wrap or reuse numbers:

- The allocator fails the transaction when `last_no` would exceed the format's maximum. The event is retried, and the alert below gets it fixed.
- An alert fires when any series passes 80% of its capacity.
- The fix is to add shards in config. New events spread into the new series.

## 5. Choosing a shard

The shard comes from the event ID:

```text
shard = letter( hash(source_event_id) mod shards )

evt-101  -> hash -> 7  mod 2 = 1 -> B
evt-102  -> hash -> 4  mod 2 = 0 -> A
evt-103  -> hash -> 11 mod 2 = 1 -> B
```

- **Even spread:** events split evenly across shards, so their counters share the load.
- **Deterministic:** the same event always maps to the same shard, which keeps tests and investigations simple.
- **Safe to change:** a duplicate of an already numbered event is caught by the invoice's unique key before a shard is chosen, so changing the shard count never renumbers anything.
- **Increasing shards:** new series start at 1 and existing ones continue.
- **Decreasing shards:** a removed shard's series stops. Its numbers stay contiguous, so it has no gap.

Shard count is set per BU and entity, so a BU can have more shards in a busy state only.

## 6. The allocation transaction

```text
 Worker                                   PostgreSQL
   |                                          |
   | BEGIN                                    |
   |----------------------------------------->|
   |                                          |
   | 1. INSERT invoice                        |
   |    ON CONFLICT (bu, source_event_id)     |
   |    DO NOTHING                            |
   |----------------------------------------->|
   |                     0 rows? duplicate:   |
   |                     ROLLBACK and stop.   |
   |                     Counter untouched.   |
   |                                          |
   | 2. Upsert invoice_counter                |
   |    last_no = last_no + 1                 |
   |    RETURNING last_no                     |
   |----------------------------------------->|
   |                     row lock taken here, |
   |                     held until COMMIT    |
   |                                          |
   | 3. Check capacity, format the number     |
   |    (in memory, no I/O)                   |
   |                                          |
   | 4. UPDATE invoice: shard, sequence_no,   |
   |    invoice_number                        |
   | 5. INSERT invoice_audit                  |
   | 6. INSERT outbox (invoice.render)        |
   |----------------------------------------->|
   |                                          |
   | COMMIT                                   |
   |----------------------------------------->|
   |                     lock released        |
```

```sql
INSERT INTO invoice_counter (entity_code, bu, financial_year, shard, last_no)
VALUES (:entityCode, :bu, :fy, :shard, 1)
ON CONFLICT (entity_code, bu, financial_year, shard)
DO UPDATE SET last_no = invoice_counter.last_no + 1
RETURNING last_no;
```

Why this order is safe:

- **Duplicate first:** the invoice insert runs before the counter, so a duplicate never touches the busy counter row.
- **Short lock:** the counter lock is taken as late as possible, and only memory work and small inserts happen before commit.
- **No deadlocks:** each transaction locks exactly one counter row.
- **No gaps:** if anything fails before commit, PostgreSQL undoes the counter increment with everything else.
- **Required transaction:** the numbering service only runs inside an existing transaction (Spring `MANDATORY`), so it can never commit a number alone.

## 7. Race scenarios

### Two different events, same series

```text
time --------------------------------------------------------------->

Worker A  INSERT ok   counter: 141 -> 142   save   COMMIT
                            |                        |
                            | lock held              | lock released
                            v                        v
Worker B  INSERT ok   counter: waits .................  142 -> 143   COMMIT

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

## 8. Crash scenarios

```text
 Step reached          Crash effect                     After restart
 --------------------  -------------------------------  ---------------------------
 Before COMMIT         Whole transaction rolls back,    Kafka redelivers the event.
                       including the counter            It gets the next free number.

 After COMMIT,         Invoice and number are saved     Kafka redelivers. The intake
 before Kafka ack                                       check finds the invoice and
                                                        stops. No second number.

 Database failover     Uncommitted work is lost,        Same as "before COMMIT".
 mid-transaction       committed work survives          Counter and invoices agree.
```

The counter and the invoices live in one database and change in one transaction, so they can never disagree.

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
Worker 2 ->|  2026-27 | A       |   one row, one writer at a time
Worker 3 ->|  last_no = 142     |
           +--------------------+
```

Throughput per series is limited by how fast one transaction commits. Shards multiply it.

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

It has no single busy row, and a rollback releases the number back to the pool. The catch is ordering: if the worker holding 142 rolls back after 143 commits, 142 goes to a later invoice. It also adds a refill job and more writes. It's kept only to measure against the counter lock, and isn't used in production unless out-of-order numbers are confirmed acceptable.

## 10. Scaling path

```text
 Load on one BU + entity
 ---------------------------------------------------------------------------->

 Step 1  Counter lock, 1 shard        simplest, strict order
 Step 2  Counter lock, N shards       config change only, throughput x N
 Step 3  Batch allocation             only if a single series must go faster
```

Each step keeps every guarantee in section 1. The load test measures the commit rate of one series first, which decides when step 2 is needed.

## 11. Data model changes

These change the current schema when numbering is built.

```sql
ALTER TABLE bu_entity
  ADD COLUMN number_shards int NOT NULL DEFAULT 1
  CHECK (number_shards BETWEEN 1 AND 26);

ALTER TABLE invoice
  ADD COLUMN shard char(1);

ALTER TABLE invoice_counter
  ADD COLUMN shard char(1) NOT NULL DEFAULT 'A';
```

| Table | Key change |
|---|---|
| `bu_entity` | `number_shards` per BU and entity |
| `invoice_counter` | Primary key becomes `(entity_code, bu, financial_year, shard)` |
| `invoice` | Unique on `(entity_code, bu, financial_year, shard, sequence_no)`. `UNIQUE (entity_code, financial_year, invoice_number)` stays as the final safety net |
| `adapter_config` | `number_format` gains the `{shard}`, `{fy}`, `{fy:full}` and `{seq:N}` tokens |

The project has no production data yet, so these go straight into the `V1` migration instead of an `ALTER`.

## 12. Code structure

```text
numbering/
  domain/entity/
    SeriesKey            entity, BU, financial year, shard
    FinancialYear        built from a date, April to March
    NumberFormat         parses and validates the template, formats numbers
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

## 13. Testing

| Test | Proves |
|---|---|
| Format unit tests | Tokens, padding, 16-character limit, allowed characters |
| Financial year unit tests | 31 March and 1 April land in different years |
| Shard unit tests | Same event, same shard. Even spread across shards |
| Capacity unit test | `last_no` past the maximum fails instead of wrapping |
| Concurrency test | 32 threads allocate in parallel. Every series is exactly 1 to N |
| Duplicate test | The same event from many threads gets one number |
| Rollback test | A rolled-back transaction leaves no gap |
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

## 14. Observability

| Metric | Use |
|---|---|
| `numbering_commit_duration_seconds{bu,entity}` | Time the counter row is locked |
| `numbering_allocations_total{bu,entity,shard}` | Spread across shards |
| `numbering_series_utilisation{bu,entity,shard}` | `last_no` against capacity, alert at 0.8 |
| `numbering_exhausted_total` | Must stay at 0 |

## 15. Open questions

- Each shard is a separate series in GST returns, as I understand it. Confirm how the returns list multiple series per GSTIN.
- Confirm how to number a late invoice dated in the previous financial year. The design continues that year's series.
- Confirm whether any BU needs a separator such as `/` or `-`, because it uses up characters from the 16.
