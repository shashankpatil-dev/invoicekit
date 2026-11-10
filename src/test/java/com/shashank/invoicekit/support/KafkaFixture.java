package com.shashank.invoicekit.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.kafka.KafkaContainer;

/**
 * Starts a real Kafka broker, the same version as deploy/compose, and points the Kafka clients at
 * it.
 *
 * <p>Import it into a test with {@code @Import(KafkaFixture.class)}. The test creates the topics it
 * needs, with the partition counts it wants.
 */
@TestConfiguration(proxyBeanMethods = false)
public class KafkaFixture {

  private static final String IMAGE = "apache/kafka:3.9.0";

  /**
   * Testcontainers' listeners with the controller bound to localhost, where its quorum voter
   * already points. Kafka 3.9 advertises the controller listener too and refuses Testcontainers'
   * default 0.0.0.0 there.
   */
  private static final String LISTENERS =
      "PLAINTEXT://0.0.0.0:9092,BROKER://0.0.0.0:9093,CONTROLLER://localhost:9094";

  @Bean
  @ServiceConnection
  KafkaContainer kafkaContainer() {
    return new KafkaContainer(IMAGE).withEnv("KAFKA_LISTENERS", LISTENERS);
  }
}
