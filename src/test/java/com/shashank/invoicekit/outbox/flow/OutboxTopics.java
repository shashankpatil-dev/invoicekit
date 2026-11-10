package com.shashank.invoicekit.outbox.flow;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.TopicPartition;
import org.springframework.kafka.core.ConsumerFactory;

/**
 * Reads back what the publisher sent to the test topics. The topics keep every earlier test's
 * records, so each test looks only at its own keys.
 */
class OutboxTopics {

  static final String FIRST = "outbox-test.first";
  static final String SECOND = "outbox-test.second";

  private static final Duration POLL = Duration.ofMillis(200);

  private final ConsumerFactory<String, String> consumerFactory;

  OutboxTopics(ConsumerFactory<String, String> consumerFactory) {
    this.consumerFactory = consumerFactory;
  }

  /** The records on a topic with one of these keys, read from its start up to its current end. */
  List<ConsumerRecord<String, String>> records(String topic, Collection<String> keys) {
    return allRecords(topic).stream().filter(record -> keys.contains(record.key())).toList();
  }

  private List<ConsumerRecord<String, String>> allRecords(String topic) {
    try (Consumer<String, String> consumer = consumerFactory.createConsumer()) {
      List<TopicPartition> partitions =
          consumer.partitionsFor(topic).stream()
              .map(info -> new TopicPartition(info.topic(), info.partition()))
              .toList();
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
}
