-- One fake entity for trying intake by hand on the local stack. Discard it any time: it is not used
-- by tests or migrations, and every value below is made up.
--
-- It sits in Maharashtra (state 27) like the sample event's recipient, so the sample stays
-- intra-state, and it has a LUT number so export declarations can resolve. Running this file again
-- changes nothing.
INSERT INTO entity (
  entity_code, legal_name, trade_name, gstin, state_code, address, registered_office_address,
  cin, pan, lut_number, authorised_signatory, bank_details, contact_email, contact_phone, terms
) VALUES (
  'LOCAL_MH', 'Fake Local Entity Private Limited', 'Fake Local', '27CCCCC2222C1Z5', '27',
  'Fake address, Mumbai 400001', 'Fake registered office, Mumbai 400001', 'FAKE-LOCAL-CIN',
  'CCCCC2222C', 'FAKE-LUT-0001', 'Fake Signatory',
  '{"accountName": "Fake Local Entity Private Limited", "ifsc": "FAKE0000002"}',
  'local@fake.example', '+91 22222 22222', 'Fake payment terms.'
) ON CONFLICT (entity_code) DO NOTHING;

-- The subscriptions BU may invoice from it on one shard, so its numbers run SUBA26... from 1.
INSERT INTO bu_entity (bu, entity_code, number_shards)
VALUES ('subscriptions', 'LOCAL_MH', 1)
ON CONFLICT (bu, entity_code) DO NOTHING;
