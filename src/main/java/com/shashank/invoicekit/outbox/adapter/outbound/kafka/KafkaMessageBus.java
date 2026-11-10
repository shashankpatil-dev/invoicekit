package com.shashank.invoicekit.outbox.adapter.outbound.kafka;

import com.shashank.invoicekit.outbox.application.port.outbound.MessageBus;
import com.shashank.invoicekit.outbox.domain.entity.OutboxMessage;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import org.springframework.kafka.core.KafkaTemplate;

/**
 * Publishes outbox messages to Kafka, inside the trace their writer saved. The template's own
 * tracing then adds a {@code traceparent} from that trace to each record, so the consumer continues
 * the writer's trace. See docs/RENDER.md section 4.
 */
public class KafkaMessageBus implements MessageBus {

  private static final String PUBLISH_SPAN = "outbox publish";

  private final KafkaTemplate<String, String> kafkaTemplate;
  private final Tracer tracer;
  private final Propagator propagator;

  public KafkaMessageBus(
      KafkaTemplate<String, String> kafkaTemplate, Tracer tracer, Propagator propagator) {
    this.kafkaTemplate = kafkaTemplate;
    this.tracer = tracer;
    this.propagator = propagator;
  }

  /**
   * Sends inside a short span that continues the saved trace. The stored headers aren't copied onto
   * the record, because the template writes the trace headers itself.
   */
  @Override
  public CompletableFuture<Void> send(OutboxMessage message) {
    if (message.headers().isEmpty()) {
      return publish(message);
    }
    Span span = propagator.extract(message.headers(), Map::get).name(PUBLISH_SPAN).start();
    try (Tracer.SpanInScope inScope = tracer.withSpan(span)) {
      return publish(message);
    } finally {
      span.end();
    }
  }

  /**
   * The template can fail before it returns a future, for example when Kafka is unreachable. That
   * failure is returned as a failed future, so one bad send doesn't stop the rest of the batch.
   */
  private CompletableFuture<Void> publish(OutboxMessage message) {
    try {
      return kafkaTemplate
          .send(message.topic(), message.key(), message.body())
          .thenAccept(acknowledged -> {});
    } catch (RuntimeException failure) {
      return CompletableFuture.failedFuture(failure);
    }
  }
}
