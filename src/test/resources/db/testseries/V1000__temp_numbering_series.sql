-- Temporary series for numbering tests. Delete this folder to discard it.
INSERT INTO entity (
  entity_code, legal_name, gstin, state_code, address, registered_office_address, cin
) VALUES (
  'TEST_NUMBERING', 'Fake Numbering Test Entity', '27AAAAA0000A1Z5', '27',
  'Fake address', 'Fake registered office', 'FAKE-CIN'
);

INSERT INTO bu_entity (bu, entity_code, number_shards) VALUES
  ('numbering-test', 'TEST_NUMBERING', 1),
  ('numbering-test-sharded', 'TEST_NUMBERING', 4);
