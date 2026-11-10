-- Trace headers saved with each outbox row, such as the W3C traceparent. The publisher continues
-- the writer's trace from them, so one trace covers intake, publishing and rendering. Rows written
-- before this change carry no trace, so they get an empty object. See docs/RENDER.md section 8.
ALTER TABLE outbox ADD COLUMN headers jsonb NOT NULL DEFAULT '{}'::jsonb;

-- Lets cleanup find old sent rows without reading the unsent ones.
CREATE INDEX outbox_sent_idx ON outbox (sent_at) WHERE sent_at IS NOT NULL;
