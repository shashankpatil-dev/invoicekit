# Invoice Superset

| | |
|---|---|
| Status | Contract v2, needs review by a CA |
| Date | 2026-11-01 |
| Scope | SaaS billing, India GST tax invoices and exports of services |

This is the complete list of fields InvoiceKit supports on an invoice, grouped by who owns each field. A BU sends only transaction facts. The platform owns organisation data, tax rates and all calculated values.

## Supported BUs

InvoiceKit serves one SaaS company, with each billing model run as a BU.

| BU | Bills | Example `sourceEventId` | Contract features used |
|---|---|---|---|
| `subscriptions` | Seat-based plans, monthly or annual | `sub-482:2026-11` | Service period, coupons as `discount`, proration by the adapter |
| `usage` | Metered API calls, storage and credits | `usage-482:2026-11` | Decimal quantities, tiers as final lines, `USAGE_SUMMARY` |
| `enterprise` | Annual custom contracts | `ent-77:2026-27` | `poNumber`, payment terms, recipient GSTIN per contract |
| `services` | Onboarding and implementation milestones | `svc-31:phase-1` | `CONTRACT_REF`, `MILESTONE` |
| `marketplace` | Paid add-ons, and commission to developers | `mkt-90:customer`, `mkt-90:commission` | One business event, two invoices |
| `international` | Customers outside India | `intl-12:2026-11` | `EXPORT_LUT`, foreign currency, declaration text |

### Marketplace rule

The entity is always the supplier. It sells the add-on to the customer, and it invoices the developer for commission. Each document has its own `sourceEventId`. Payouts to developers are outside InvoiceKit.

## Entity

**Keep one simple `entity` table, with one row per GSTIN.** A GSTIN is tied to one state, and one GSTIN can serve many BUs. BUs send `entityCode`, so supplier details stay in one place.

Legal details such as the CIN repeat across rows of the same company. Split them out only if a real need appears.

## Field groups

| Group | Owner | Source |
|---|---|---|
| Entity | Platform | `entity` table |
| State | Platform | `gst_state` table |
| Declarations | Platform | `declaration` table |
| Tax | Platform | `tax_code` table |
| Transaction | BU | `invoice.requested` event |
| Calculated | Platform | Generated during processing |
| BU extras | BU | Event `attributes`, limited to `allowed_fields` |

## Entity fields

| Field | Required | Notes |
|---|---|---|
| `entityCode` | Yes | Internal key, such as `ACME_MH` |
| `legalName` | Yes | Registered company name |
| `tradeName` | No | Shown if different from the legal name |
| `gstin` | Yes | One per row |
| `stateName` | Yes | State of this GSTIN |
| `stateCode` | Yes | Two-digit GST state code |
| `address` | Yes | Address for this GSTIN, including PIN |
| `registeredOfficeAddress` | Yes | Registered office of the company |
| `cin` | Yes | Company identification number |
| `pan` | No | Not listed in Rule 46, and shown by choice |
| `lutNumber` | For exports | Letter of Undertaking reference |
| `authorisedSignatory` | No | Not needed on electronic invoices |
| `bankDetails` | No | Shown for payment |
| `contactEmail`, `contactPhone` | No | Shown for support |
| `terms` | No | Standard invoice terms |
| `logo` | No | Template asset |

## Tax fields

| Field | Required | Notes |
|---|---|---|
| `taxCode` | Yes | Platform key that the BU sends, such as `SAAS_18` |
| `hsnCode` | Yes | SAC code for the service |
| `gstRate` | Yes | Total GST rate |
| `cessRate` | No | Only where cess applies |
| `effectiveFrom` | Yes | Start date |
| `effectiveTo` | No | End date |

`taxCode` exists because one HSN or SAC code can have more than one rate. The BU chooses a `taxCode`, and the platform owns the code and rate behind it.

## State fields

`gst_state` holds `stateCode` and `stateName`. The platform uses it to validate state codes and to find the place of supply name.

## Declarations

Declarations are legal or policy statements owned by the platform. BUs never send declaration text. Their event values decide which ones apply.

| Field | Notes |
|---|---|
| `code` | Stable key, such as `EXPORT_LUT` |
| `body` | Wording, which can use placeholders like `{lutNumber}` |
| `bu` | Limits it to one BU |
| `supplyCategory` | Limits it to `DOMESTIC` or `EXPORT_LUT` |
| `reverseCharge` | Limits it to `true` or `false` |
| `recipientRegistered` | Limits it to registered or unregistered recipients |
| `sortOrder` | Print order |
| `effectiveFrom`, `effectiveTo` | Dates it applies, checked against `invoiceDate` |

An empty condition matches every invoice, and every filled condition must match. A shared `DeclarationResolver` selects the rows, fills in placeholders, and saves the final text in the payload.

| Code | Applies when |
|---|---|
| `GENERAL` | Always |
| `REVERSE_CHARGE` | `reverseCharge` is `true` |
| `EXPORT_LUT` | `supplyCategory` is `EXPORT_LUT` |
| `SUBSCRIPTION_PERIOD` | `bu` is `subscriptions` |
| `ELECTRONIC_INVOICE` | Always |

To change wording, end-date the old row and add a new one. Saved invoices keep their original text. To add a declaration, add a row, with no code or template change.

## Transaction fields

### Reference

| Field | Required | Notes |
|---|---|---|
| `bu` | Yes | Selects the adapter |
| `sourceEventId` | Yes | Idempotency key |
| `entityCode` | Yes | Selects the supplier |
| `invoiceDate` | Yes | Sets the financial year and tax rates |
| `supplyCategory` | Yes | `DOMESTIC` or `EXPORT_LUT` |
| `placeOfSupplyStateCode` | For `DOMESTIC` | BU decides this, and the platform looks up the state name |
| `reverseCharge` | Yes | `true` or `false`. Always `false` for exports |
| `currency` | Yes | `INR` for domestic. ISO code for exports, such as `USD` |
| `exchangeRate` | If not `INR` | INR value of one unit of `currency` |

### Recipient

| Field | Required | Notes |
|---|---|---|
| `name` | Yes | |
| `gstin` | If registered in India | Absent for unregistered and foreign recipients |
| `foreignTaxId` | No | Shown for foreign recipients |
| `address` | Registered, export, or supply of ₹50,000 or more | |
| `stateName`, `stateCode` | Registered, or supply of ₹50,000 or more | Indian recipients only |
| `countryCode` | For exports | ISO code, such as `US` |
| `pin` | No | |
| `email` | No | Used for the notification |

For an unregistered Indian recipient below ₹50,000, the address and state are optional unless the recipient asks for them. The place of supply is still required.

### Line items

| Field | Required | Notes |
|---|---|---|
| `description` | Yes | Plan name, usage item or milestone |
| `taxCode` | Yes | Selects the SAC code and tax rate |
| `quantity` | Yes | Decimal, up to 3 places, such as seats or GB |
| `unit` | Yes | Unit code, such as `NOS` or `OTH` |
| `unitPrice` | Yes | Before tax, up to 4 decimal places |
| `discount` | No | Coupon or negotiated discount, reducing the taxable value |
| `servicePeriodStart` | No | First day covered |
| `servicePeriodEnd` | No | Last day covered |

Proration, tiers and coupons are worked out by the adapter. The event may send raw billing data, but each saved line holds only final values.

### Optional

`dueDate`, `paymentTerms`, `poNumber`.

## BU extras

Fields used only by some BUs go in `attributes`. Each must be listed in `InvoiceField` and in the BU's `allowed_fields`.

| `InvoiceField` | Attribute | Typical BU |
|---|---|---|
| `SUBSCRIPTION_ID` | `subscriptionId` | `subscriptions`, `usage` |
| `PLAN_NAME` | `planName` | `subscriptions` |
| `BILLING_CYCLE` | `billingCycle` | `subscriptions` |
| `COUPON_CODE` | `couponCode` | `subscriptions` |
| `USAGE_SUMMARY` | `usageSummary` | `usage` |
| `CONTRACT_REF` | `contractRef` | `enterprise`, `services` |
| `MILESTONE` | `milestone` | `services` |
| `ADDON_ID` | `addonId` | `marketplace` |
| `DEVELOPER_ID` | `developerId` | `marketplace` |

`InvoiceField` also includes `DUE_DATE`, `PAYMENT_TERMS` and `PO_NUMBER`, so each BU can choose whether to allow them.

## Calculated fields

| Field | Rule |
|---|---|
| `invoiceNumber` | Assigned during numbering, then added to the payload in the same transaction |
| `financialYear` | April to March from `invoiceDate`, such as `2026-27` |
| `supplyType` | `EXPORT` for `EXPORT_LUT`. Otherwise `INTRA_STATE` if entity state equals place of supply, or `INTER_STATE` |
| `placeOfSupply` | State code and name, or country code and name for exports |
| `lineNo` | Line position, starting at 1 |
| `hsnCode`, `gstRate` | Copied from `tax_code` for each line. `gstRate` is `0.00` for exports |
| `taxableValue` | Quantity × unit price, less discount |
| `cgstRate`, `cgstAmount` | Half of GST, for intra-state supply |
| `sgstRate`, `sgstAmount` | Half of GST, for intra-state supply |
| `igstRate`, `igstAmount` | Full GST, for inter-state supply |
| `cessAmount` | Where cess applies |
| `lineTotal` | Taxable value plus all tax for the line |
| `totalTaxableValue` | Sum of line values |
| `totalCgst`, `totalSgst`, `totalIgst`, `totalCess` | Sum of each tax across lines |
| `totalTax` | Sum of line taxes |
| `totalValue` | Taxable value plus tax |
| `amountInWords` | From `totalValue`, in the invoice currency |
| `totalTaxableValueInr`, `totalValueInr` | Invoice totals × `exchangeRate`. Equal to the totals for `INR` |
| `declarations` | Matching platform declarations, as `code` and final `text`, in order |

## Rules

- `entityCode` must exist, and the BU must be allowed to use it.
- `placeOfSupplyStateCode` must exist in `gst_state`.
- `countryCode` must be a valid ISO code, and not `IN`, for exports.
- `taxCode` must have a rate on `invoiceDate`.
- `gstin` values must pass format validation.
- Every declaration placeholder must resolve, or the invoice goes to the DLQ before numbering.
- An export needs `lutNumber` on the entity, and must have no GST.
- A foreign currency needs `exchangeRate`, and `DOMESTIC` supply must use `INR`.
- The saved payload stores a snapshot of entity and tax values, so old invoices never change.
- Tax rates are chosen by `invoiceDate`.
- `invoiceNumber` is at most 16 characters, and uses only letters, digits, `-` and `/`.
- The number series is `entity + bu + financialYear + shard`. See [Numbering Design](NUMBERING.md).
- Each BU needs its own prefix, because BUs share a GSTIN and the number must stay unique for that GSTIN.

## Rounding

A shared `TaxCalculator` applies one rule for every BU:

1. Calculate each line's taxable value and each tax amount separately.
2. Round each amount to 2 decimals with `HALF_UP`.
3. Add the rounded line values to get the totals. Totals are never recalculated from the invoice total.

INR totals are converted from the rounded invoice totals, then rounded to 2 decimals. Invoice totals are not rounded to the nearest rupee.

## Domestic example

A `subscriptions` invoice with entity and place of supply both in Maharashtra (`27`), so tax splits into CGST and SGST. All names, identifiers, rates and prices in these examples are made up.

### Event from the BU

```json
{
  "schemaVersion": 2,
  "bu": "subscriptions",
  "sourceEventId": "sub-482:2026-11",
  "entityCode": "ACME_MH",
  "invoiceDate": "2026-11-01",
  "supplyCategory": "DOMESTIC",
  "placeOfSupplyStateCode": "27",
  "reverseCharge": false,
  "currency": "INR",
  "dueDate": "2026-11-15",
  "paymentTerms": "Net 14",
  "recipient": {
    "name": "Example Traders Private Limited",
    "gstin": "27ABCDE1234F1Z5",
    "address": "12 Market Road, Pune",
    "stateName": "Maharashtra",
    "stateCode": "27",
    "pin": "411001",
    "email": "accounts@example.com"
  },
  "lineItems": [
    {
      "description": "Growth plan",
      "taxCode": "SAAS_18",
      "quantity": "10",
      "unit": "NOS",
      "unitPrice": "1500.0000",
      "discount": "1500.00",
      "servicePeriodStart": "2026-11-01",
      "servicePeriodEnd": "2026-11-30"
    },
    {
      "description": "Extra storage",
      "taxCode": "SAAS_18",
      "quantity": "20",
      "unit": "OTH",
      "unitPrice": "50.0000",
      "discount": "0.00",
      "servicePeriodStart": "2026-11-01",
      "servicePeriodEnd": "2026-11-30"
    }
  ],
  "attributes": {
    "subscriptionId": "sub-482",
    "planName": "Growth",
    "billingCycle": "MONTHLY",
    "couponCode": "WELCOME10"
  }
}
```

### Saved payload

The platform saves this in `invoice.payload`, and sends it to the document engine.

```json
{
  "contractVersion": 2,
  "invoiceId": "018f3c1e-7b0a-7d3e-9e0a-2f4c9a6d1b11",
  "bu": "subscriptions",
  "sourceEventId": "sub-482:2026-11",
  "invoiceNumber": "SUBA260000000142",
  "invoiceDate": "2026-11-01",
  "financialYear": "2026-27",
  "supplyCategory": "DOMESTIC",
  "supplyType": "INTRA_STATE",
  "placeOfSupply": {
    "stateName": "Maharashtra",
    "stateCode": "27"
  },
  "reverseCharge": false,
  "currency": "INR",
  "exchangeRate": "1.0000",
  "dueDate": "2026-11-15",
  "paymentTerms": "Net 14",
  "declarations": [
    {
      "code": "GENERAL",
      "text": "We declare that this invoice shows the actual price of the services described and that all particulars are true and correct."
    },
    {
      "code": "SUBSCRIPTION_PERIOD",
      "text": "Subscription charges cover the service period shown against each line."
    },
    {
      "code": "ELECTRONIC_INVOICE",
      "text": "This is an electronically generated invoice and does not require a signature."
    }
  ],
  "supplier": {
    "entityCode": "ACME_MH",
    "legalName": "Acme Cloud Private Limited",
    "tradeName": "Acme Cloud",
    "gstin": "27AAAAA0000A1Z5",
    "stateName": "Maharashtra",
    "stateCode": "27",
    "address": "45 Tech Park, Mumbai 400001",
    "registeredOfficeAddress": "1 Corporate Park, Mumbai 400001",
    "cin": "U72900MH2020PTC000000",
    "pan": "AAAAA0000A",
    "lutNumber": "AD2700000000000",
    "bankDetails": {
      "accountName": "Acme Cloud Private Limited",
      "accountNumber": "000000000000",
      "ifsc": "ABCD0000001"
    },
    "contactEmail": "billing@acme.example",
    "contactPhone": "+91 00000 00000",
    "terms": "Payment due as per the agreed terms."
  },
  "recipient": {
    "name": "Example Traders Private Limited",
    "gstin": "27ABCDE1234F1Z5",
    "address": "12 Market Road, Pune",
    "stateName": "Maharashtra",
    "stateCode": "27",
    "pin": "411001",
    "email": "accounts@example.com"
  },
  "lineItems": [
    {
      "lineNo": 1,
      "description": "Growth plan",
      "taxCode": "SAAS_18",
      "hsnCode": "998314",
      "quantity": "10",
      "unit": "NOS",
      "unitPrice": "1500.0000",
      "discount": "1500.00",
      "servicePeriodStart": "2026-11-01",
      "servicePeriodEnd": "2026-11-30",
      "taxableValue": "13500.00",
      "gstRate": "18.00",
      "cgstRate": "9.00",
      "cgstAmount": "1215.00",
      "sgstRate": "9.00",
      "sgstAmount": "1215.00",
      "igstRate": "0.00",
      "igstAmount": "0.00",
      "cessAmount": "0.00",
      "lineTotal": "15930.00"
    },
    {
      "lineNo": 2,
      "description": "Extra storage",
      "taxCode": "SAAS_18",
      "hsnCode": "998314",
      "quantity": "20",
      "unit": "OTH",
      "unitPrice": "50.0000",
      "discount": "0.00",
      "servicePeriodStart": "2026-11-01",
      "servicePeriodEnd": "2026-11-30",
      "taxableValue": "1000.00",
      "gstRate": "18.00",
      "cgstRate": "9.00",
      "cgstAmount": "90.00",
      "sgstRate": "9.00",
      "sgstAmount": "90.00",
      "igstRate": "0.00",
      "igstAmount": "0.00",
      "cessAmount": "0.00",
      "lineTotal": "1180.00"
    }
  ],
  "totals": {
    "totalTaxableValue": "14500.00",
    "totalCgst": "1305.00",
    "totalSgst": "1305.00",
    "totalIgst": "0.00",
    "totalCess": "0.00",
    "totalTax": "2610.00",
    "totalValue": "17110.00",
    "amountInWords": "Rupees Seventeen Thousand One Hundred Ten Only",
    "totalTaxableValueInr": "14500.00",
    "totalValueInr": "17110.00"
  },
  "attributes": {
    "subscriptionId": "sub-482",
    "planName": "Growth",
    "billingCycle": "MONTHLY",
    "couponCode": "WELCOME10"
  }
}
```

### Inter-state difference

If the recipient and place of supply were in Karnataka (`29`), `supplyType` would be `INTER_STATE`. For line 1, CGST and SGST would be `0.00`, and `igstRate` and `igstAmount` would be `18.00` and `2430.00`.

## Export example

An `international` invoice in USD, zero-rated under LUT.

### Event from the BU

```json
{
  "schemaVersion": 2,
  "bu": "international",
  "sourceEventId": "intl-12:2026-11",
  "entityCode": "ACME_MH",
  "invoiceDate": "2026-11-01",
  "supplyCategory": "EXPORT_LUT",
  "reverseCharge": false,
  "currency": "USD",
  "exchangeRate": "85.0000",
  "recipient": {
    "name": "Example Software Inc",
    "foreignTaxId": "00-0000000",
    "address": "100 Market Street, San Francisco, CA 94105",
    "countryCode": "US",
    "email": "billing@example.com"
  },
  "lineItems": [
    {
      "description": "Pro plan",
      "taxCode": "SAAS_18",
      "quantity": "10",
      "unit": "NOS",
      "unitPrice": "100.0000",
      "discount": "0.00",
      "servicePeriodStart": "2026-11-01",
      "servicePeriodEnd": "2026-11-30"
    }
  ],
  "attributes": {
    "subscriptionId": "sub-910",
    "planName": "Pro",
    "billingCycle": "MONTHLY"
  }
}
```

### Saved payload

The `supplier` snapshot is the same as the domestic example, so it's shortened here.

```json
{
  "contractVersion": 2,
  "invoiceId": "018f3c1e-7b0a-7d3e-9e0a-2f4c9a6d1b12",
  "bu": "international",
  "sourceEventId": "intl-12:2026-11",
  "invoiceNumber": "INTA260000000017",
  "invoiceDate": "2026-11-01",
  "financialYear": "2026-27",
  "supplyCategory": "EXPORT_LUT",
  "supplyType": "EXPORT",
  "placeOfSupply": {
    "countryName": "United States",
    "countryCode": "US"
  },
  "reverseCharge": false,
  "currency": "USD",
  "exchangeRate": "85.0000",
  "declarations": [
    {
      "code": "GENERAL",
      "text": "We declare that this invoice shows the actual price of the services described and that all particulars are true and correct."
    },
    {
      "code": "EXPORT_LUT",
      "text": "Supply meant for export under Letter of Undertaking without payment of integrated tax. LUT: AD2700000000000"
    },
    {
      "code": "ELECTRONIC_INVOICE",
      "text": "This is an electronically generated invoice and does not require a signature."
    }
  ],
  "supplier": {
    "entityCode": "ACME_MH",
    "legalName": "Acme Cloud Private Limited",
    "gstin": "27AAAAA0000A1Z5",
    "lutNumber": "AD2700000000000"
  },
  "recipient": {
    "name": "Example Software Inc",
    "foreignTaxId": "00-0000000",
    "address": "100 Market Street, San Francisco, CA 94105",
    "countryCode": "US",
    "email": "billing@example.com"
  },
  "lineItems": [
    {
      "lineNo": 1,
      "description": "Pro plan",
      "taxCode": "SAAS_18",
      "hsnCode": "998314",
      "quantity": "10",
      "unit": "NOS",
      "unitPrice": "100.0000",
      "discount": "0.00",
      "servicePeriodStart": "2026-11-01",
      "servicePeriodEnd": "2026-11-30",
      "taxableValue": "1000.00",
      "gstRate": "0.00",
      "cgstRate": "0.00",
      "cgstAmount": "0.00",
      "sgstRate": "0.00",
      "sgstAmount": "0.00",
      "igstRate": "0.00",
      "igstAmount": "0.00",
      "cessAmount": "0.00",
      "lineTotal": "1000.00"
    }
  ],
  "totals": {
    "totalTaxableValue": "1000.00",
    "totalCgst": "0.00",
    "totalSgst": "0.00",
    "totalIgst": "0.00",
    "totalCess": "0.00",
    "totalTax": "0.00",
    "totalValue": "1000.00",
    "amountInWords": "US Dollars One Thousand Only",
    "totalTaxableValueInr": "85000.00",
    "totalValueInr": "85000.00"
  },
  "attributes": {
    "subscriptionId": "sub-910",
    "planName": "Pro",
    "billingCycle": "MONTHLY"
  }
}
```

## Out of scope

- Non-SaaS supplies, including goods and e-way bills
- IRN, QR codes and e-invoice submission
- UTGST as a separate tax head
- Credit notes and debit notes
- Consolidated invoices
- Developer payouts

IRN and QR codes would be needed for B2B invoices at real SaaS scale. They're left out to keep this project focused.
