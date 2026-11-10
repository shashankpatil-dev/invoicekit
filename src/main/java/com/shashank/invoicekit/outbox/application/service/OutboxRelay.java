package com.shashank.invoicekit.outbox.application.service;

import com.shashank.invoicekit.outbox.application.port.outbound.MessageBus;
import com.shashank.invoicekit.outbox.application.port.outbound.OutboxRepository;
import com.shashank.invoicekit.outbox.domain.entity.OutboxMessage;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.springframework.transaction.annotation.Transactional;

/**
 * Moves committed outbox rows to the message bus, one batch per transaction. A row is marked sent
 * only after the bus acknowledges it, so a crash or a failed send leaves it for a later batch, and
 * delivery is at least once. See docs/RENDER.md section 4.
 */
public class OutboxRelay {

  private final OutboxRepository repository;
  private final MessageBus messageBus;
  private final int batchSize;
  private final Duration sendTimeout;

  /**
   * @param batchSize the most rows one batch claims and sends
   * @param sendTimeout the longest wait for a batch's acknowledgements
   */
  public OutboxRelay(
      OutboxRepository repository, MessageBus messageBus, int batchSize, Duration sendTimeout) {
    this.repository = repository;
    this.messageBus = messageBus;
    this.batchSize = batchSize;
    this.sendTimeout = sendTimeout;
  }

  /**
   * Claims a batch of unsent rows, sends them all, waits for their acknowledgements, and marks the
   * acknowledged ones sent. The claim holds row locks until commit, so other publishers skip these
   * rows meanwhile.
   *
   * @return how many messages were sent and marked
   */
  @Transactional
  public int publishBatch() {
    List<Delivery> deliveries = repository.claimUnsent(batchSize).stream().map(this::send).toList();
    awaitAll(deliveries);
    List<UUID> acknowledged =
        deliveries.stream().filter(Delivery::acknowledged).map(Delivery::id).toList();
    repository.markSent(acknowledged);
    return acknowledged.size();
  }

  private Delivery send(OutboxMessage message) {
    return new Delivery(message.id(), messageBus.send(message));
  }

  /**
   * Waits until every send has finished, or the send timeout has passed. Failures aren't thrown
   * here, because each delivery's own outcome decides whether its row is marked.
   */
  private void awaitAll(List<Delivery> deliveries) {
    CompletableFuture.allOf(
            deliveries.stream().map(Delivery::ack).toArray(CompletableFuture[]::new))
        .handle((allDone, failure) -> null)
        .completeOnTimeout(null, sendTimeout.toMillis(), TimeUnit.MILLISECONDS)
        .join();
  }

  /** One message on its way, with the future that completes when the bus acknowledges it. */
  private record Delivery(UUID id, CompletableFuture<Void> ack) {

    boolean acknowledged() {
      return ack.state() == Future.State.SUCCESS;
    }
  }
}
