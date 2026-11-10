#!/bin/sh
# Publishes test invoice.requested events to the local Kafka, for trying intake by hand.
# Discardable: nothing else uses it.
#
# Usage: deploy/compose/scripts/send-invoice-events.sh [count]
#
# Each event is the domestic sample from src/test/resources/payloads/intake, sent from the fake
# LOCAL_MH entity in deploy/compose/seed/local-entity.sql, with its own sourceEventId so every event
# becomes a new invoice. Events go through the console producer inside the Kafka container, so
# nothing needs installing on this machine.
set -eu

COUNT="${1:-1}"
SCRIPT_DIR=$(cd "$(dirname "$0")" && pwd)
REPO_DIR=$(cd "$SCRIPT_DIR/../../.." && pwd)
COMPOSE_FILE="$REPO_DIR/deploy/compose/docker-compose.yml"
SAMPLE_EVENT="$REPO_DIR/src/test/resources/payloads/intake/subscriptions-domestic-event.json"

# The sample on one line, because the console producer reads one message per line.
SAMPLE=$(tr -d '\n' < "$SAMPLE_EVENT")

# A run ID keeps event IDs unique across runs, so running the script again sends new events.
RUN_ID=$(date +%s)

# Prints one "key|event" line per event, swapping in the local entity and a new event ID.
events() {
  i=1
  while [ "$i" -le "$COUNT" ]; do
    source_event_id="local-$RUN_ID-$i"
    event=$(printf '%s' "$SAMPLE" \
      | sed -e "s/\"sub-482:2026-11\"/\"$source_event_id\"/" -e 's/"ACME_MH"/"LOCAL_MH"/')
    printf 'subscriptions:%s|%s\n' "$source_event_id" "$event"
    i=$((i + 1))
  done
}

events | docker compose -f "$COMPOSE_FILE" exec -T kafka \
  /opt/kafka/bin/kafka-console-producer.sh --bootstrap-server localhost:9092 \
  --topic invoice.requested --property parse.key=true --property key.separator='|'

echo "Sent $COUNT event(s) with sourceEventId local-$RUN_ID-1 to local-$RUN_ID-$COUNT"
