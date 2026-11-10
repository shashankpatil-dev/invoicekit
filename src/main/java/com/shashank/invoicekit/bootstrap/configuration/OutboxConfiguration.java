package com.shashank.invoicekit.bootstrap.configuration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shashank.invoicekit.outbox.adapter.inbound.OutboxSchedule;
import com.shashank.invoicekit.outbox.adapter.outbound.kafka.KafkaMessageBus;
import com.shashank.invoicekit.outbox.adapter.outbound.postgres.PostgresOutboxRepository;
import com.shashank.invoicekit.outbox.adapter.outbound.tracing.MicrometerTraceHeaders;
import com.shashank.invoicekit.outbox.application.port.outbound.MessageBus;
import com.shashank.invoicekit.outbox.application.port.outbound.OutboxRepository;
import com.shashank.invoicekit.outbox.application.port.outbound.TraceHeaders;
import com.shashank.invoicekit.outbox.application.service.OutboxCleanup;
import com.shashank.invoicekit.outbox.application.service.OutboxRelay;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Connects the outbox to PostgreSQL, Kafka and tracing. Every profile can read the current trace
 * for the outbox rows it writes, but only the {@code jobs} profile publishes and cleans up rows on
 * a schedule. See docs/RENDER.md section 4.
 */
@Configuration
public class OutboxConfiguration {

  /** What writers save with their outbox rows, so publishing continues their trace. */
  @Bean
  public TraceHeaders traceHeaders(Tracer tracer, ObjectProvider<Propagator> propagator) {
    return new MicrometerTraceHeaders(tracer, propagatorOrNone(propagator));
  }

  @Bean
  public OutboxRepository outboxRepository(JdbcClient jdbcClient, ObjectMapper objectMapper) {
    return new PostgresOutboxRepository(jdbcClient, objectMapper);
  }

  @Bean
  public MessageBus messageBus(
      KafkaTemplate<String, String> kafkaTemplate,
      Tracer tracer,
      ObjectProvider<Propagator> propagator) {
    return new KafkaMessageBus(kafkaTemplate, tracer, propagatorOrNone(propagator));
  }

  @Bean
  public OutboxRelay outboxRelay(
      OutboxRepository repository, MessageBus messageBus, OutboxProperties properties) {
    return new OutboxRelay(
        repository, messageBus, properties.batchSize(), properties.sendTimeout());
  }

  @Bean
  public OutboxCleanup outboxCleanup(OutboxRepository repository, OutboxProperties properties) {
    return new OutboxCleanup(repository, properties.retention(), properties.cleanupBatchSize());
  }

  /**
   * Spring Boot leaves out the propagator when tracing is switched off, as it is in tests. Rows
   * then carry no trace, and messages are sent without one.
   */
  private static Propagator propagatorOrNone(ObjectProvider<Propagator> propagator) {
    return propagator.getIfAvailable(() -> Propagator.NOOP);
  }

  /** Runs the publisher and cleanup, with Spring's scheduling, only in the {@code jobs} profile. */
  @Configuration
  @Profile("jobs")
  @EnableScheduling
  static class OutboxScheduling {

    @Bean
    public OutboxSchedule outboxSchedule(
        OutboxRelay relay, OutboxCleanup cleanup, OutboxProperties properties) {
      return new OutboxSchedule(
          relay,
          cleanup,
          properties.batchSize(),
          properties.pollInterval(),
          properties.cleanupInterval());
    }
  }
}
