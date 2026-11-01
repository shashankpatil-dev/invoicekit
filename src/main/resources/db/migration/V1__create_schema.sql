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
  bu           text NOT NULL,
  entity_code  text NOT NULL REFERENCES entity (entity_code),
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
  UNIQUE (entity_code, bu, financial_year, sequence_no),
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
  last_no         bigint NOT NULL,
  PRIMARY KEY (entity_code, bu, financial_year)
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
