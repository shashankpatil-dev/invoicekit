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
| Jaeger UI | http://localhost:16687 |
| Jaeger OTLP HTTP | `localhost:4320` |

Start Debezium with `docker compose --profile cdc up -d`. It listens on `localhost:8083`.

## Try intake locally

Send test events through Kafka and watch them become numbered invoices. Run these from the repository root.

1. Start the stack.

   ```bash
   docker compose -f deploy/compose/docker-compose.yml up -d
   ```

2. Run the app as the intake worker. On first start it creates the schema, so leave it running and use another terminal for the rest.

   ```bash
   ./gradlew bootRun --args='--spring.profiles.active=intake'
   ```

3. Add the fake `LOCAL_MH` entity that the test events invoice from. Running it again changes nothing.

   ```bash
   docker compose -f deploy/compose/docker-compose.yml exec -T postgres \
     psql -U invoicekit -d invoicekit < deploy/compose/seed/local-entity.sql
   ```

4. Send events. The count defaults to 1, and every event gets a new `sourceEventId`.

   ```bash
   deploy/compose/scripts/send-invoice-events.sh 10
   ```

5. See the invoices, the yearly partition each landed in, and their outbox rows.

   ```bash
   docker compose -f deploy/compose/docker-compose.yml exec postgres psql -U invoicekit -d invoicekit -c \
     "SELECT i.invoice_number, i.source_event_id, i.tableoid::regclass AS partition, i.status, o.topic AS outbox
      FROM invoice i JOIN outbox o ON o.invoice_id = i.id WHERE i.entity_code = 'LOCAL_MH' ORDER BY i.invoice_number"
   ```

6. See rejected events on the dead-letter topic, with their headers.

   ```bash
   docker compose -f deploy/compose/docker-compose.yml exec kafka /opt/kafka/bin/kafka-console-consumer.sh \
     --bootstrap-server localhost:9092 --topic invoice.requested.dlq --from-beginning --timeout-ms 5000 \
     --property print.key=true --property print.headers=true
   ```

Traces for each event are in Jaeger at http://localhost:16687, under the `invoicekit` service. The seed and the script are only for trying things by hand, and can be deleted.

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
