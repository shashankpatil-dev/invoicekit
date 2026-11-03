#!/bin/sh
set -eu

BOOTSTRAP=kafka:9092
DAY_MS=86400000

create() {
  /opt/kafka/bin/kafka-topics.sh --bootstrap-server "$BOOTSTRAP" --create --if-not-exists \
    --topic "$1" --partitions "$2" --replication-factor 1 \
    --config retention.ms="$3"
}

create invoice.requested 24 $((7 * DAY_MS))
create invoice.requested.dlq 6 $((14 * DAY_MS))
create invoice.render 24 $((7 * DAY_MS))
create invoice.generated 12 $((7 * DAY_MS))
