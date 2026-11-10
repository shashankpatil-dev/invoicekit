package com.shashank.invoicekit.outbox.adapter.outbound.kafka;

import static org.assertj.core.api.Assertions.assertThat;

import com.shashank.invoicekit.outbox.domain.entity.OutboxMessage;
import com.shashank.invoicekit.support.TracingFixture;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.apache.kafka.clients.producer.MockProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.KafkaTemplate;

/**
 * Sends outbox messages through a Kafka template traced as the application's is, into a mock
 * producer, and checks that each record continues the trace its writer saved.
 */
class KafkaMessageBusTest {

  private static final String TOPIC = "invoice.render";
  private static final String KEY = "invoice-1";
  private static final String BODY = "{\"schemaVersion\": 1}";
  private static final String TRACEPARENT_HEADER = "traceparent";
  private static final String STORED_TRACE_ID = "4bf92f3577b34da6a3ce929d0e0e4736";
  private static final String STORED_TRACEPARENT = "00-" + STORED_TRACE_ID + "-00f067aa0ba902b7-01";

  private final TracingFixture tracing = new TracingFixture();
  private final MockProducer<String, String> producer =
      new MockProducer<>(true, new StringSerializer(), new StringSerializer());
  private final KafkaMessageBus bus =
      new KafkaMessageBus(tracedTemplate(), tracing.tracer(), tracing.propagator());

  @Test
  void recordContinuesTheStoredTraceWithOneTraceparent() {
    bus.send(message(Map.of(TRACEPARENT_HEADER, STORED_TRACEPARENT))).join();

    assertThat(sentRecord().headers().headers(TRACEPARENT_HEADER))
        .singleElement()
        .extracting(KafkaMessageBusTest::traceId)
        .isEqualTo(STORED_TRACE_ID);
  }

  @Test
  void messageWithoutAStoredTraceIsStillSent() {
    bus.send(message(Map.of())).join();

    ProducerRecord<String, String> sent = sentRecord();
    assertThat(sent.topic()).isEqualTo(TOPIC);
    assertThat(sent.key()).isEqualTo(KEY);
    assertThat(sent.value()).isEqualTo(BODY);
  }

  @Test
  void sendThatFailsAtOnceIsReportedThroughItsFuture() {
    producer.close();

    CompletableFuture<Void> ack = bus.send(message(Map.of()));

    assertThat(ack).isCompletedExceptionally();
  }

  private KafkaTemplate<String, String> tracedTemplate() {
    KafkaTemplate<String, String> template = new KafkaTemplate<>(() -> producer);
    template.setObservationEnabled(true);
    template.setObservationRegistry(tracing.senderObservations());
    return template;
  }

  private ProducerRecord<String, String> sentRecord() {
    assertThat(producer.history()).hasSize(1);
    return producer.history().getFirst();
  }

  private static OutboxMessage message(Map<String, String> headers) {
    return new OutboxMessage(UUID.randomUUID(), TOPIC, KEY, BODY, headers);
  }

  private static String traceId(Header traceparent) {
    return TracingFixture.traceId(new String(traceparent.value(), StandardCharsets.UTF_8));
  }
}
