package com.shashank.invoicekit.bootstrap.configuration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shashank.invoicekit.invoice.adapter.inbound.InvoiceRequestedListener;
import com.shashank.invoicekit.invoice.adapter.kafka.FailedEventLog;
import com.shashank.invoicekit.invoice.adapter.kafka.RejectionHeaders;
import com.shashank.invoicekit.invoice.application.service.IntakeService;
import com.shashank.invoicekit.invoice.domain.exception.RejectedEventException;
import java.util.Map;
import org.apache.kafka.common.TopicPartition;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.KafkaOperations;
import org.springframework.kafka.listener.ConcurrentMessageListenerContainer;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.ExponentialBackOff;

/**
 * Connects intake to Kafka in the {@code intake} worker profile: which topic it reads, as which
 * consumer group, with how many threads, and what happens when an event fails. See docs/INTAKE.md
 * section 8.
 *
 * <p>The container comes from Spring Boot's listener factory, so it keeps the shared settings in
 * config/kafka.properties and config/tracing.properties: manual acks and tracing.
 */
@Configuration
@Profile("intake")
public class IntakeKafkaConfiguration {

  private static final String REQUESTED_TOPIC = "invoice.requested";
  private static final String DEAD_LETTER_TOPIC = "invoice.requested.dlq";
  private static final String CONSUMER_GROUP = "invoicekit-intake";

  /**
   * A negative partition lets Kafka pick one from the record's key. The dead-letter topic has fewer
   * partitions than the source, so the source partition may not exist there.
   */
  private static final int KAFKA_CHOOSES_PARTITION = -1;

  /** Reads {@code invoice.requested} and passes each message to the intake listener. */
  @Bean
  public ConcurrentMessageListenerContainer<?, ?> invoiceRequestedContainer(
      ConcurrentKafkaListenerContainerFactory<?, ?> factory,
      KafkaOperations<?, ?> kafkaTemplate,
      IntakeService intakeService,
      ObjectMapper objectMapper,
      IntakeProperties properties) {
    ConcurrentMessageListenerContainer<?, ?> container = factory.createContainer(REQUESTED_TOPIC);
    container.getContainerProperties().setGroupId(CONSUMER_GROUP);
    container.setConcurrency(properties.concurrency());
    container.setCommonErrorHandler(errorHandler(kafkaTemplate, properties));
    container.setupMessageListener(new InvoiceRequestedListener(intakeService, objectMapper));
    return container;
  }

  /**
   * Sends a rejected event straight to the dead-letter topic, and retries any other failure with
   * growing waits until it succeeds, so a database outage holds the partition instead of flooding
   * the dead-letter topic. Either way the offset is committed only after the event is handled, and
   * every failure is logged.
   */
  private static DefaultErrorHandler errorHandler(
      KafkaOperations<?, ?> kafkaTemplate, IntakeProperties properties) {
    DefaultErrorHandler errorHandler =
        new DefaultErrorHandler(deadLetterPublisher(kafkaTemplate), retryWaits(properties));
    errorHandler.setClassifications(Map.of(RejectedEventException.class, false), true);
    errorHandler.setRetryListeners(new FailedEventLog());
    return errorHandler;
  }

  /** Publishes under the source record's key, adding why the event was rejected. */
  private static DeadLetterPublishingRecoverer deadLetterPublisher(
      KafkaOperations<?, ?> kafkaTemplate) {
    DeadLetterPublishingRecoverer publisher =
        new DeadLetterPublishingRecoverer(
            kafkaTemplate,
            (message, failure) -> new TopicPartition(DEAD_LETTER_TOPIC, KAFKA_CHOOSES_PARTITION));
    publisher.setHeadersFunction(new RejectionHeaders());
    return publisher;
  }

  /** Waits that grow from the initial to the longest interval, with no limit on attempts. */
  private static ExponentialBackOff retryWaits(IntakeProperties properties) {
    ExponentialBackOff waits = new ExponentialBackOff();
    waits.setInitialInterval(properties.retryInitialInterval().toMillis());
    waits.setMaxInterval(properties.retryMaxInterval().toMillis());
    return waits;
  }
}
