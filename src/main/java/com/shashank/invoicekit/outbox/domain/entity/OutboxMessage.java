package com.shashank.invoicekit.outbox.domain.entity;

import java.util.Map;
import java.util.UUID;

/**
 * One message waiting in the outbox to be published.
 *
 * @param id the outbox row's ID
 * @param topic the topic it goes to
 * @param key the message key, which decides its partition
 * @param body the message as JSON text
 * @param headers trace headers saved by the writer, such as {@code traceparent}, or none
 */
public record OutboxMessage(
    UUID id, String topic, String key, String body, Map<String, String> headers) {

  public OutboxMessage {
    headers = Map.copyOf(headers);
  }
}
