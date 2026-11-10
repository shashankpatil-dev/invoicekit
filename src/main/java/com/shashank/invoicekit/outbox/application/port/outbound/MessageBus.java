package com.shashank.invoicekit.outbox.application.port.outbound;

import com.shashank.invoicekit.outbox.domain.entity.OutboxMessage;
import java.util.concurrent.CompletableFuture;

/** Publishes outbox messages to the message broker. */
public interface MessageBus {

  /**
   * Starts sending one message without waiting for it.
   *
   * @return a future that completes when the broker acknowledges the message, or completes
   *     exceptionally when the send fails
   */
  CompletableFuture<Void> send(OutboxMessage message);
}
