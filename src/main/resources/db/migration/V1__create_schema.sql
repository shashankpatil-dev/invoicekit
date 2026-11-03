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

-- The shard is one letter, A to Z, in the invoice number, so a BU allows at most 26 shards.
CREATE TABLE bu_entity (
  bu             text NOT NULL,
  entity_code    text NOT NULL REFERENCES entity (entity_code),
  number_shards  int NOT NULL DEFAULT 1,
  PRIMARY KEY (bu, entity_code),
  CONSTRAINT bu_entity_number_shards_range CHECK (number_shards BETWEEN 1 AND 26)
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
  CONSTRAINT invoice_source_event_unique UNIQUE (bu, source_event_id),
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
  -- The row is inserted before numbering, then shard, sequence and number are set together in the
  -- same transaction. So they are either all missing or all present, never partly set.
  CONSTRAINT invoice_numbered_together
    CHECK (num_nulls(shard, sequence_no, invoice_number) IN (0, 3))
);

CREATE INDEX invoice_stuck_numbered_idx ON invoice (updated_at)
  WHERE status = 'NUMBERED';
CREATE INDEX invoice_stuck_stored_idx ON invoice (updated_at)
  WHERE status = 'STORED';

-- Every invoice updates one of these rows. Half-empty pages let PostgreSQL keep each new row
-- version in the same page (a HOT update), so this small, very busy table doesn't bloat.
CREATE TABLE invoice_counter (
  entity_code     text NOT NULL REFERENCES entity (entity_code),
  bu              text NOT NULL,
  financial_year  text NOT NULL,
  shard           char(1) NOT NULL,
  last_no         bigint NOT NULL,
  PRIMARY KEY (entity_code, bu, financial_year, shard),
  CONSTRAINT invoice_counter_financial_year_format
    CHECK (financial_year ~ '^[0-9]{4}-[0-9]{2}$'),
  CONSTRAINT invoice_counter_shard_format CHECK (shard ~ '^[A-Z]$'),
  CONSTRAINT invoice_counter_last_no_positive CHECK (last_no > 0)
) WITH (fillfactor = 50);

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
