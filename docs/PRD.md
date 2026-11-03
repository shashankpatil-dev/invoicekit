# InvoiceKit PRD

| | |
|---|---|
| Status | Draft, document engine built |
| Date | 2026-11-01 |
| Related | [HLD](HLD.md), [LLD](LLD.md) |

## 1. Summary

InvoiceKit is an event-driven service that generates compliant invoices for a SaaS company's business units (BUs) from one platform. The BUs are `subscriptions`, `usage`, `enterprise`, `services`, `marketplace` and `international`. A BU publishes an event, and InvoiceKit validates it, calculates tax, assigns a unique sequence number, renders a PDF, stores it, notifies the recipient, and later archives it at lower cost.

A new BU joins by adding an adapter, its configuration and a template. The core flow stays unchanged.

## 2. Problem

Each BU has its own invoice fields, tax handling and layout. Building invoice generation separately per BU causes these problems:

- Tax and numbering logic gets duplicated, and each copy can have its own compliance bug.
- Duplicate events, retries and crashes can create duplicate invoices or waste sequence numbers.
- A burst of events can overload rendering.
- Old invoices must stay retrievable for years, while keeping every file in hot storage is expensive.

## 3. Goals

| ID | Goal |
|---|---|
| G1 | Generate exactly one invoice per BU event, despite retries, duplicates and crashes |
| G2 | Assign unique, consecutive numbers within each series |
| G3 | Add a BU without changing the core flow |
| G4 | Sustain 20 rps on average and absorb 100 rps bursts for 10 minutes |
| G5 | Recover from component failures with no invoice lost |
| G6 | Reduce storage cost for invoices older than 90 days while keeping them retrievable |
| G7 | Publish measured results: throughput, latency, and failure test outcomes |

## 4. Non-goals

- Billing outside SaaS, including goods and e-way bills.
- Calculating subscriptions, proration or usage. BU systems own billing logic.
- A UI for designing templates.
- Payment collection, ledgers or accounting.
- Direct submission to government e-invoicing portals.
- Credit notes and invoice amendments in the first version.
- Multiple document engines. Typst is the only engine for now.
- Cloud deployment in the first version. The project runs locally.

## 5. Users

| User | Need |
|---|---|
| BU system | Publish an event and trust that one invoice results |
| BU developer | Add or change an adapter and template without learning the core flow |
| Operations engineer | See stuck invoices, re-drive failures and replay rejected events |
| Compliance or finance reviewer | Audit numbers, status history and the original tax values |
| Invoice consumer | Download any invoice, however old |

## 6. User stories

- As a BU system, I publish `invoice.requested` once or many times and get one invoice.
- As a BU developer, I add `adaption/<bu>/` and a Typst template, and the service starts handling that BU.
- As a BU developer, I get a startup failure if my config allows a field the platform doesn't support.
- As an operations engineer, I can find an invoice by ID, BU or event ID and see its status.
- As an operations engineer, I can replay a rejected event after fixing the cause.
- As a compliance reviewer, I can re-render an old invoice and get the original values, whatever today's tax rates are.
- As an invoice consumer, I use one API for recent and archived invoices.

## 7. Functional requirements

### Intake

| ID | Requirement |
|---|---|
| FR1 | Consume `invoice.requested` events from Kafka |
| FR2 | Reject malformed or unsupported events to a dead-letter topic, with the reason |
| FR3 | Treat `bu` + `source_event_id` as the idempotency key |

### Adapters and contract

| ID | Requirement |
|---|---|
| FR4 | Route each event to its adapter by BU key |
| FR5 | Maintain a superset of supported invoice fields, defined in [Invoice Superset](INVOICE_SUPERSET.md), with each BU allowing a subset of the optional fields |
| FR6 | Reject a payload that contains fields the BU doesn't allow |
| FR7 | Validate adapter config at startup and fail fast if it's invalid |
| FR8 | Calculate tax from shared, versioned, date-effective rates |
| FR9 | Keep templates on the BU side, with one Typst template per BU |

### Numbering

| ID | Requirement |
|---|---|
| FR10 | Assign a number only after the payload passes validation |
| FR11 | Keep a separate series per entity, BU, financial year and shard, so a busy BU can be split into several series |
| FR12 | Never leave a gap caused by a rollback, duplicate or crash |
| FR13 | Save the number and final payload together in one transaction |
| FR14 | Never reuse or discard a committed number |

### Generation and storage

| ID | Requirement |
|---|---|
| FR15 | Render a PDF from the BU template and the saved payload |
| FR16 | Store the PDF in object storage at a path built from the invoice ID |
| FR17 | Record the storage URI and checksum |
| FR18 | Never let a duplicate worker replace a stored invoice |

### Communication

| ID | Requirement |
|---|---|
| FR19 | Publish `invoice.generated` after an invoice is stored |
| FR20 | Send a notification and record delivery |
| FR21 | Avoid sending duplicate notifications where the system can detect them |

### Recovery and operations

| ID | Requirement |
|---|---|
| FR22 | Run a recovery cron that re-drives stuck invoices with the same number and payload |
| FR23 | Mark an invoice `FAILED` and raise an alert after 5 recovery attempts |
| FR24 | Offer a manual re-drive for `FAILED` invoices |
| FR25 | Offer a formal cancellation that keeps the number and an audit record |
| FR26 | Offer a way to replay dead-letter events |

### Archive and retrieval

| ID | Requirement |
|---|---|
| FR27 | Bundle invoices older than 90 days into daily tar files |
| FR28 | Move bundles from Standard to Archive storage |
| FR29 | Delete original objects only after verifying the bundle |
| FR30 | Retrieve any single invoice by API, wherever it's stored |

## 8. Non-functional requirements

| Area | Requirement |
|---|---|
| Throughput | 20 rps average, about 1.7M invoices a day. 100 rps burst for 10 minutes, about 60k invoices |
| Reliability | Zero lost invoices, duplicate invoices or duplicate numbers in failure tests |
| Latency | Baseline first, then set targets per step and end to end. The stretch goal of p95 under 100ms and p99 under 150ms applies per step, not to the full pipeline |
| Scalability | Scale API, intake and render workers separately. Publish the highest measured throughput and first bottleneck |
| Availability | Redis failure causes slowdown only. A render or storage outage delays invoices without losing them |
| Durability | PostgreSQL is the source of truth. Redis holds only data that can be rebuilt |
| Security | Encrypt in transit and at rest in the cloud, require auth on APIs, and keep invoice PII out of logs |
| Auditability | Keep the saved payload and status history for every invoice |
| Retention | Follow the legal retention period of the target country, to be confirmed |
| Observability | Per-step timing, Kafka lag, stuck invoice counts, and alerts |
| Portability | Run the full stack locally with Docker Compose |

## 9. Success metrics

| Metric | Target |
|---|---|
| Lost invoices in failure tests | 0 |
| Duplicate invoices or numbers | 0 |
| Sustained load | 20 rps with no consumer lag growth |
| Burst load | 100 rps for 10 minutes, with backlog cleared afterwards |
| Automatic recovery | Every injected failure recovers without manual repair |
| New BU effort | Adapter, config and template only, with no change in `invoice/` |
| Archive storage saving | Measured against the Standard-only baseline, using published cloud pricing |

## 10. Scope by release

| Release | Scope |
|---|---|
| M1 | `subscriptions` end to end, numbering, render, local storage, notification |
| M2 | Recovery cron, dead-letter replay, failure tests |
| M3 | `enterprise`, which proves the contract with a very different template |
| M4 | Load test with polling, then Debezium CDC and comparison |
| M5 | `usage`, `services`, `marketplace` and multiple series load test |
| M6 | `international`, archive and retrieval |

## 11. Assumptions

- Each BU sends a stable, unique `source_event_id`.
- Each event contains its entity, place of supply and invoice date.
- A 50KB average PDF is used for planning until real templates exist.

## 12. Current status

The document engine is complete for now, with the `subscriptions` template and the Typst renderer. It held 100 rps with zero errors in load tests, and those tests cover rendering only. See [Load Test Results](LOAD_TEST.md).

## 13. Risks

| Risk | Mitigation |
|---|---|
| The busiest series limits throughput | Load test it, keep the transaction short, split series where allowed |
| Typst CPU limits render throughput | Scale render workers separately and cap concurrency per pod |
| The superset becomes a dumping ground | Version the contract and review new fields |
| A bad tax rate produces wrong invoices | Effective dates, validation, and audit of the saved payload |
| Debezium downtime grows PostgreSQL WAL | Alert on slot lag and cap retained WAL |
| Local tests don't reflect the cloud | Label results as local, and repeat in the cloud later |

## 14. Open questions

- What is the legal retention period?
- What are the formal rules for cancelling an invoice?
- What does the final archive bundling and retrieval design look like? That design will be merged into the LLD.
