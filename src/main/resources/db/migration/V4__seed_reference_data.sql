-- Reference data intake needs before it can accept an order. Entities are company data, so they
-- are never seeded here; they live in the test series and local seeds.

-- GST state codes as the GST portal lists them, including codes kept for older registrations:
-- 25 (Daman and Diu, merged into 26 in 2020) and 28 (Andhra Pradesh before it was divided).
-- Check this list against the GST portal before real use.
INSERT INTO gst_state (state_code, state_name) VALUES
  ('01', 'Jammu and Kashmir'),
  ('02', 'Himachal Pradesh'),
  ('03', 'Punjab'),
  ('04', 'Chandigarh'),
  ('05', 'Uttarakhand'),
  ('06', 'Haryana'),
  ('07', 'Delhi'),
  ('08', 'Rajasthan'),
  ('09', 'Uttar Pradesh'),
  ('10', 'Bihar'),
  ('11', 'Sikkim'),
  ('12', 'Arunachal Pradesh'),
  ('13', 'Nagaland'),
  ('14', 'Manipur'),
  ('15', 'Mizoram'),
  ('16', 'Tripura'),
  ('17', 'Meghalaya'),
  ('18', 'Assam'),
  ('19', 'West Bengal'),
  ('20', 'Jharkhand'),
  ('21', 'Odisha'),
  ('22', 'Chhattisgarh'),
  ('23', 'Madhya Pradesh'),
  ('24', 'Gujarat'),
  ('25', 'Daman and Diu'),
  ('26', 'Dadra and Nagar Haveli and Daman and Diu'),
  ('27', 'Maharashtra'),
  ('28', 'Andhra Pradesh (Before Division)'),
  ('29', 'Karnataka'),
  ('30', 'Goa'),
  ('31', 'Lakshadweep'),
  ('32', 'Kerala'),
  ('33', 'Tamil Nadu'),
  ('34', 'Puducherry'),
  ('35', 'Andaman and Nicobar Islands'),
  ('36', 'Telangana'),
  ('37', 'Andhra Pradesh'),
  ('38', 'Ladakh'),
  ('97', 'Other Territory');

-- 18% GST on software as a service. The SAC is an example; confirm it with a CA before real use.
INSERT INTO tax_code (tax_code, hsn_code, gst_rate, cess_rate, effective_from)
VALUES ('SAAS_18', '998314', 18.00, 0, DATE '2017-07-01');

-- The subscriptions business unit: the optional fields its orders may send, and its number format.
INSERT INTO adapter_config (bu, version, allowed_fields, number_format, active)
VALUES (
  'subscriptions', 1,
  '["DUE_DATE", "PAYMENT_TERMS", "PO_NUMBER", "SUBSCRIPTION_ID", "PLAN_NAME", "BILLING_CYCLE", "COUPON_CODE"]',
  'SUB{shard}{fy}{seq:10}', true
);
