# InvoiceKit

Event-driven invoice generation for multiple business units, built with Java 21, Spring Boot, Kafka, PostgreSQL, Redis and Typst.

This repository holds the project base. Application code isn't written yet.

## Docs

- [PRD](docs/PRD.md)
- [HLD](docs/HLD.md)
- [LLD](docs/LLD.md)
- [Invoice Superset](docs/INVOICE_SUPERSET.md)
- [Code Conventions](docs/CONVENTIONS.md)

## CI

GitHub Actions runs `./gradlew build` on every push to `main` and every pull request. Run the same command locally before pushing.

## Local infrastructure

```bash`
cd deploy/local
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
deploy/local/        Docker Compose and local config
loadtest/k6/         Load tests
failuretest/         Failure test scenarios
```
