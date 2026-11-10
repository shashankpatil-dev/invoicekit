-- Temporary entity for intake tests. Delete this folder to discard it.
-- It sits in Maharashtra like the superset's example supplier, so the domestic example event stays
-- intra-state. Its GSTIN differs from TEST_NUMBERING's, because a GSTIN belongs to one entity.
INSERT INTO entity (
  entity_code, legal_name, trade_name, gstin, state_code, address, registered_office_address,
  cin, pan, lut_number, authorised_signatory, bank_details, contact_email, contact_phone, terms
) VALUES (
  'TEST_INTAKE', 'Fake Intake Test Entity', 'Fake Intake', '27BBBBB1111B1Z5', '27',
  'Fake address, Mumbai 400001', 'Fake registered office, Mumbai 400001', 'FAKE-INTAKE-CIN',
  'BBBBB1111B', 'AD2799999999999', 'Fake Signatory',
  '{"accountName": "Fake Intake Test Entity", "ifsc": "FAKE0000001"}',
  'intake@test.example', '+91 11111 11111', 'Fake payment terms.'
);

-- The subscriptions BU may invoice from it, on one shard, so its numbers run SUBA26... from 1.
INSERT INTO bu_entity (bu, entity_code, number_shards) VALUES ('subscriptions', 'TEST_INTAKE', 1);
