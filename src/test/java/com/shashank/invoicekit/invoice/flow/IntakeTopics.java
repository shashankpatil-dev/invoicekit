package com.shashank.invoicekit.invoice.flow;

import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.TopicPartition;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.kafka.core.KafkaTemplate;

/**
 * Sends events to intake's topic and reads back what intake did with them: whether their offsets
 * were committed and what reached the dead-letter topic.
 */
class IntakeTopics {

  static final String REQUESTED = "invoice.requested";
  static final String DEAD_LETTERS = "invoice.requested.dlq";

  private static final String CONSUMER_GROUP = "invoicekit-intake";
  private static final Duration POLL = Duration.ofMillis(200);

  private final KafkaTemplate<String, String> kafkaTemplate;
  private final ConsumerFactory<String, String> consumerFactory;
  private final KafkaAdmin kafkaAdmin;

  IntakeTopics(
      KafkaTemplate<String, String> kafkaTemplate,
      ConsumerFactory<String, String> consumerFactory,
      KafkaAdmin kafkaAdmin) {
    this.kafkaTemplate = kafkaTemplate;
    this.consumerFactory = consumerFactory;
    this.kafkaAdmin = kafkaAdmin;
  }

  /** The key a BU gives an event: its business unit and its event ID. */
  static String key(String sourceEventId) {
    return IntakeEvents.BUSINESS_UNIT + ":" + sourceEventId;
  }

  /** Sends a message as a BU would, waiting until Kafka has stored it. */
  RecordMetadata send(String sourceEventId, String message) {
    return kafkaTemplate.send(REQUESTED, key(sourceEventId), message).join().getRecordMetadata();
  }

  /** Waits until intake has committed the offset of a sent message, so it won't be redelivered. */
  void awaitAcknowledged(RecordMetadata sent) {
    TopicPartition partition = new TopicPartition(sent.topic(), sent.partition());
    await().until(() -> committedOffset(partition) > sent.offset());
  }

  /** Waits for the dead letter with this key, and returns it. */
  ConsumerRecord<String, String> awaitDeadLetter(String key) {
    return await().until(() -> deadLetter(key), Optional::isPresent).orElseThrow();
  }

  /** How many messages the dead-letter topic holds. */
  long deadLetterCount() {
    try (Consumer<String, String> consumer = consumerFactory.createConsumer()) {
      return consumer.endOffsets(deadLetterPartitions(consumer)).values().stream()
          .mapToLong(Long::longValue)
          .sum();
    }
  }

  /** The next offset intake will read from a partition, or -1 before its first commit there. */
  private long committedOffset(TopicPartition partition)
      throws ExecutionException, InterruptedException {
    try (Admin admin = Admin.create(kafkaAdmin.getConfigurationProperties())) {
      Map<TopicPartition, OffsetAndMetadata> committed =
          admin.listConsumerGroupOffsets(CONSUMER_GROUP).partitionsToOffsetAndMetadata().get();
      OffsetAndMetadata offset = committed.get(partition);
      return offset == null ? -1 : offset.offset();
    }
  }

  private Optional<ConsumerRecord<String, String>> deadLetter(String key) {
    return allDeadLetters().stream().filter(record -> key.equals(record.key())).findFirst();
  }

  /** Reads the dead-letter topic from its start up to its current end. */
  private List<ConsumerRecord<String, String>> allDeadLetters() {
    try (Consumer<String, String> consumer = consumerFactory.createConsumer()) {
      List<TopicPartition> partitions = deadLetterPartitions(consumer);
      consumer.assign(partitions);
      consumer.seekToBeginning(partitions);
      Map<TopicPartition, Long> ends = consumer.endOffsets(partitions);
      List<ConsumerRecord<String, String>> records = new ArrayList<>();
      while (partitions.stream()
          .anyMatch(partition -> consumer.position(partition) < ends.get(partition))) {
        consumer.poll(POLL).forEach(records::add);
      }
      return records;
    }
  }

  private static List<TopicPartition> deadLetterPartitions(Consumer<String, String> consumer) {
    return consumer.partitionsFor(DEAD_LETTERS).stream()
        .map(info -> new TopicPartition(info.topic(), info.partition()))
        .toList();
  }
}
