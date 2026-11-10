# InvoiceKit

Event-driven invoice generation for multiple business units, built with Java 21, Spring Boot, Kafka, PostgreSQL, Redis and Typst.

The document engine and numbering modules are complete for now. The invoice flow, archive and BU adapters aren't built yet.

## Docs

- [PRD](docs/PRD.md)
- [HLD](docs/HLD.md)
- [LLD](docs/LLD.md)
- [Invoice Superset](docs/INVOICE_SUPERSET.md)
- [Numbering Design](docs/NUMBERING.md)
- [Intake Design](docs/INTAKE.md)
- [Load Test Results](docs/LOAD_TEST.md)
- [Code Conventions](docs/CONVENTIONS.md)

## CI

GitHub Actions runs `./gradlew build` on every push to `main` and every pull request. Run the same command locally before pushing. The tests need Docker.

## Local infrastructure

```bash
cd deploy/compose
docker compose up -d
```

| Service | Address |
|---|---|
| PostgreSQL | `localhost:5433` |
| Kafka | `localhost:9094` |
| Redis | `localhost:6380` |
| fake-gcs-server | `localhost:4443` |
| Mailpit SMTP | `localhost:1025` |
| Mailpit UI | http://localhost:8025 |
| Document engine | `localhost:3002` |
| Prometheus | http://localhost:9091 |
| Grafana | http://localhost:3001 |

Start Debezium with `docker compose --profile cdc up -d`. It listens on `localhost:8083`.

## Layout

```text
src/main/java/com/shashank/invoicekit/
  bootstrap/         Spring configuration
  invoice/           Core invoice flow
  numbering/         Sequence number allocation
  documentengine/    Rendering
  archive/           Bundling and retrieval
  adaption/          One package per BU
src/main/resources/
  adaption/          One template folder per BU
  db/migration/      Flyway migrations
  config/            Engineering settings, one file per topic
document-engine/     Typst rendering service, built as its own image
deploy/compose/      Docker Compose for the whole stack on one machine
deploy/helm/         One Helm chart per service, with values files per environment
loadtest/k6/         k6 load test script and Docker runner
loadtest/kind/       kind cluster runner for Kubernetes load tests
```
