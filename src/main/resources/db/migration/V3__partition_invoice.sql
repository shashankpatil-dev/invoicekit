-- Partitions the invoice table while it is still empty. No environment has invoice, outbox or
-- invoice_audit rows yet, so dropping and recreating invoice loses nothing. Partitioning a full
-- table later would mean copying every row. See docs/INTAKE.md section 9.

-- A foreign key to a partitioned table must include its partition key, and outbox and audit rows
-- only carry the invoice ID. The intake transaction writes the invoice, its outbox row and its
-- audit row in one statement, so they can't disagree without these keys.
ALTER TABLE outbox DROP CONSTRAINT outbox_invoice_id_fkey;
ALTER TABLE invoice_audit DROP CONSTRAINT invoice_audit_invoice_id_fkey;

DROP TABLE invoice;

-- PostgreSQL needs the partition key, financial_year, inside every unique key. Both number keys
-- already contain it, so they stay fully unique. The duplicate event key does not, so it moves to
-- invoice_event below.
CREATE TABLE invoice (
  id                uuid NOT NULL,
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
  PRIMARY KEY (id, financial_year),
  -- Each shard is its own series, so a sequence number repeats across shards, never within one.
  CONSTRAINT invoice_series_sequence_unique
    UNIQUE (entity_code, bu, financial_year, shard, sequence_no),
  -- Final safety net: one GSTIN never issues the same number twice in a financial year.
  CONSTRAINT invoice_number_unique UNIQUE (entity_code, financial_year, invoice_number),
  CONSTRAINT invoice_financial_year_format CHECK (financial_year ~ '^[0-9]{4}-[0-9]{2}$'),
  CONSTRAINT invoice_shard_format CHECK (shard ~ '^[A-Z]$'),
  CONSTRAINT invoice_sequence_positive CHECK (sequence_no > 0),
  -- GST Rule 46: at most 16 characters, only letters, digits, hyphen and slash.
  CONSTRAINT invoice_number_format CHECK (invoice_number ~ '^[A-Za-z0-9/-]{1,16}$'),
  -- Intake inserts every invoice already numbered. This check still makes sure shard, sequence
  -- and number are never partly set.
  CONSTRAINT invoice_numbered_together
    CHECK (num_nulls(shard, sequence_no, invoice_number) IN (0, 3))
) PARTITION BY LIST (financial_year);

CREATE INDEX invoice_stuck_numbered_idx ON invoice (updated_at)
  WHERE status = 'NUMBERED';
CREATE INDEX invoice_stuck_stored_idx ON invoice (updated_at)
  WHERE status = 'STORED';

-- One partition per financial year, created ahead. An invoice for a year without a partition is
-- rejected, and a test fails when next year's partition is missing, so add the next one in time.
CREATE TABLE invoice_2025_26 PARTITION OF invoice FOR VALUES IN ('2025-26');
CREATE TABLE invoice_2026_27 PARTITION OF invoice FOR VALUES IN ('2026-27');
CREATE TABLE invoice_2027_28 PARTITION OF invoice FOR VALUES IN ('2027-28');
CREATE TABLE invoice_2028_29 PARTITION OF invoice FOR VALUES IN ('2028-29');
CREATE TABLE invoice_2029_30 PARTITION OF invoice FOR VALUES IN ('2029-30');
CREATE TABLE invoice_2030_31 PARTITION OF invoice FOR VALUES IN ('2030-31');

-- The only place a repeated event is caught. Intake inserts here first with ON CONFLICT DO
-- NOTHING, so a second delivery of the same event inserts nothing and creates no invoice.
-- Partitioning by a hash of source_event_id keeps the key fully unique, because the key contains
-- the partition key, and spreads the rows evenly forever. invoice_id has no foreign key, because
-- invoice is partitioned and its row is written later in the same transaction.
CREATE TABLE invoice_event (
  bu               text NOT NULL,
  source_event_id  text NOT NULL,
  invoice_id       uuid NOT NULL,
  financial_year   text NOT NULL,
  created_at       timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (bu, source_event_id),
  CONSTRAINT invoice_event_financial_year_format CHECK (financial_year ~ '^[0-9]{4}-[0-9]{2}$')
) PARTITION BY HASH (source_event_id);

-- Creates invoice_event_00 to invoice_event_15.
DO $$
BEGIN
  FOR remainder IN 0..15 LOOP
    EXECUTE format(
      'CREATE TABLE invoice_event_%s PARTITION OF invoice_event FOR VALUES WITH (MODULUS 16, REMAINDER %s)',
      lpad(remainder::text, 2, '0'), remainder);
  END LOOP;
END
$$;
