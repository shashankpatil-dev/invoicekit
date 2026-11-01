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
                    ('NUMBERED', 'STORED', 'DELIVERED', 'FAILED', 'CANCELLED')),
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
  id           uuid PRIMARY KEY,
  invoice_id   uuid NOT NULL REFERENCES invoice (id),
  topic        text NOT NULL,
  message_key  text NOT NULL,
  body         jsonb NOT NULL,
  created_at   timestamptz NOT NULL DEFAULT now(),
  sent_at      timestamptz
);

CREATE INDEX outbox_unsent_idx ON outbox (created_at) WHERE sent_at IS NULL;

CREATE TABLE adapter_config (
  bu              text NOT NULL,
  version         int NOT NULL,
  allowed_fields  jsonb NOT NULL,
  number_format   text NOT NULL,
  active          boolean NOT NULL DEFAULT false,
  PRIMARY KEY (bu, version)
);

CREATE UNIQUE INDEX adapter_config_one_active_idx ON adapter_config (bu) WHERE active;

CREATE TABLE tax_rate (
  tax_code        text NOT NULL,
  region          text NOT NULL,
  rate            numeric(6, 3) NOT NULL,
  effective_from  date NOT NULL,
  effective_to    date,
  PRIMARY KEY (tax_code, region, effective_from)
);

CREATE TABLE invoice_audit (
  id           bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  invoice_id   uuid NOT NULL REFERENCES invoice (id),
  from_status  text,
  to_status    text NOT NULL,
  actor        text NOT NULL,
  reason       text,
  created_at   timestamptz NOT NULL DEFAULT now()
);
