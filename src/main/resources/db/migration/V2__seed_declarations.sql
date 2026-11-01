-- Starting declaration rules. The wording needs review by a CA before real use.
-- A NULL condition matches every invoice.
INSERT INTO declaration
  (code, body, bu, supply_category, reverse_charge, recipient_registered, sort_order, effective_from)
VALUES
  ('GENERAL',
   'We declare that this invoice shows the actual price of the services described and that all particulars are true and correct.',
   NULL, NULL, NULL, NULL, 10, DATE '2017-07-01'),
  ('REVERSE_CHARGE',
   'Tax on this invoice is payable by the recipient on a reverse charge basis.',
   NULL, NULL, true, NULL, 20, DATE '2017-07-01'),
  ('EXPORT_LUT',
   'Supply meant for export under Letter of Undertaking without payment of integrated tax. LUT: {lutNumber}',
   NULL, 'EXPORT_LUT', NULL, NULL, 20, DATE '2017-07-01'),
  ('SUBSCRIPTION_PERIOD',
   'Subscription charges cover the service period shown against each line.',
   'subscriptions', NULL, NULL, NULL, 30, DATE '2017-07-01'),
  ('ELECTRONIC_INVOICE',
   'This is an electronically generated invoice and does not require a signature.',
   NULL, NULL, NULL, NULL, 90, DATE '2017-07-01');
