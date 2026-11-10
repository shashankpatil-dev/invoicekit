package com.shashank.invoicekit.outbox.flow;

import static com.shashank.invoicekit.outbox.flow.OutboxTopics.FIRST;
import static com.shashank.invoicekit.outbox.flow.OutboxTopics.SECOND;
import static org.assertj.core.api.Assertions.assertThat;

import com.shashank.invoicekit.bootstrap.configuration.OutboxProperties;
import com.shashank.invoicekit.outbox.adapter.inbound.OutboxSchedule;
import com.shashank.invoicekit.outbox.application.port.outbound.MessageBus;
import com.shashank.invoicekit.outbox.application.port.outbound.OutboxRepository;
import com.shashank.invoicekit.outbox.application.service.OutboxCleanup;
import com.shashank.invoicekit.outbox.application.service.OutboxRelay;
import com.shashank.invoicekit.support.KafkaFixture;
import com.shashank.invoicekit.support.PostgresFixture;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.IntStream;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.scheduling.config.IntervalTask;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;

/**
 * Publishes outbox rows from a real PostgreSQL to a real Kafka broker and checks what
 * docs/RENDER.md section 2 promises: every unsent row reaches its topic and is marked sent, a row
 * is only marked once acknowledged, and publishers running together never send a row twice.
 *
 * <p>The whole application runs with the relay wired as in production, but without the {@code jobs}
 * profile, so no schedule publishes behind the tests' backs. A small batch size makes a backlog
 * easy to build. Rows commit for real, so each test deletes them afterwards.
 */
@SpringBootTest(
    webEnvironment = WebEnvironment.NONE,
    properties = "invoicekit.outbox.batch-size=" + OutboxRelayTest.BATCH_SIZE)
@Import({
  PostgresFixture.class,
  KafkaFixture.class,
  OutboxRelayTest.Topics.class,
  OutboxRows.class,
  OutboxTopics.class
})
class OutboxRelayTest {

  static final int BATCH_SIZE = 3;

  /** Enough rows for many batches, so two racing relays overlap. */
  private static final int RACE_ROWS = 60;

  private static final Duration SHORT_SEND_TIMEOUT = Duration.ofMillis(200);

  @Autowired private OutboxRelay relay;
  @Autowired private OutboxCleanup cleanup;
  @Autowired private OutboxRepository repository;
  @Autowired private OutboxProperties properties;
  @Autowired private OutboxRows rows;
  @Autowired private OutboxTopics topics;

  @AfterEach
  void deleteRows() {
    rows.deleteAll();
  }

  @Test
  void unsentRowsReachTheirTopicsWithTheirKeyAndBodyAndAreMarkedSent() {
    String firstKey = newKey();
    String secondKey = newKey();
    UUID first = rows.unsent(FIRST, firstKey);
    UUID second = rows.unsent(SECOND, secondKey);

    assertThat(relay.publishBatch()).isEqualTo(2);

    assertThat(topics.records(FIRST, List.of(firstKey)))
        .singleElement()
        .extracting(ConsumerRecord::value)
        .isEqualTo(OutboxRows.body(firstKey));
    assertThat(topics.records(SECOND, List.of(secondKey)))
        .singleElement()
        .extracting(ConsumerRecord::value)
        .isEqualTo(OutboxRows.body(secondKey));
    assertThat(List.of(first, second)).allMatch(rows::isSent);
  }

  @Test
  void rowWithUnreadableHeadersIsStillSentWithoutItsTrace() {
    String key = newKey();
    UUID id = rows.unsent(FIRST, key);
    rows.replaceHeaders(id, "[\"not\", \"an object\"]");

    assertThat(relay.publishBatch()).isEqualTo(1);

    assertThat(topics.records(FIRST, List.of(key))).hasSize(1);
    assertThat(rows.isSent(id)).isTrue();
  }

  @Test
  void rowsAlreadySentAreNotSentAgain() {
    String key = newKey();
    rows.sent(FIRST, key, Duration.ofMinutes(1));

    assertThat(relay.publishBatch()).isZero();

    assertThat(topics.records(FIRST, List.of(key))).isEmpty();
  }

  @Test
  void failedSendStaysUnsentWhileTheOthersAreMarkedSent() {
    String failingKey = newKey();
    UUID failed = rows.unsent(FIRST, failingKey);
    UUID delivered = rows.unsent(FIRST, newKey());
    MessageBus bus =
        acknowledgingAllBut(
            failingKey, CompletableFuture.failedFuture(new IllegalStateException()));

    assertThat(relayOver(bus).publishBatch()).isEqualTo(1);

    assertThat(rows.isSent(failed)).isFalse();
    assertThat(rows.isSent(delivered)).isTrue();
  }

  @Test
  void sendNotAcknowledgedWithinTheTimeoutStaysUnsent() {
    String slowKey = newKey();
    UUID slow = rows.unsent(FIRST, slowKey);
    UUID delivered = rows.unsent(FIRST, newKey());
    MessageBus bus = acknowledgingAllBut(slowKey, new CompletableFuture<>());

    assertThat(relayOver(bus).publishBatch()).isEqualTo(1);

    assertThat(rows.isSent(slow)).isFalse();
    assertThat(rows.isSent(delivered)).isTrue();
  }

  @Test
  void twoRelaysRunningAtOnceNeverSendARowTwice() throws InterruptedException {
    List<String> keys = IntStream.range(0, RACE_ROWS).mapToObj(row -> newKey()).toList();
    keys.forEach(key -> rows.unsent(FIRST, key));
    Callable<Void> publishUntilEmpty =
        () -> {
          while (relay.publishBatch() > 0) {
            // Keep publishing until this relay finds nothing left to claim.
          }
          return null;
        };

    try (ExecutorService pods = Executors.newFixedThreadPool(2)) {
      pods.invokeAll(List.of(publishUntilEmpty, publishUntilEmpty));
    }

    assertThat(topics.records(FIRST, keys))
        .extracting(ConsumerRecord::key)
        .containsExactlyInAnyOrderElementsOf(keys);
    assertThat(rows.unsentCount()).isZero();
  }

  @Test
  void scheduleDrainsABacklogBiggerThanOneBatchInOneRun() {
    List<UUID> backlog =
        IntStream.range(0, 3 * BATCH_SIZE + 1)
            .mapToObj(row -> rows.unsent(FIRST, newKey()))
            .toList();
    ScheduledTaskRegistrar registrar = new ScheduledTaskRegistrar();
    new OutboxSchedule(
            relay,
            cleanup,
            properties.batchSize(),
            properties.pollInterval(),
            properties.cleanupInterval())
        .configureTasks(registrar);

    registrar.getFixedDelayTaskList().getFirst().getRunnable().run();

    assertThat(registrar.getFixedDelayTaskList())
        .extracting(IntervalTask::getIntervalDuration)
        .containsExactly(properties.pollInterval(), properties.cleanupInterval());
    assertThat(backlog).allMatch(rows::isSent);
  }

  /** A relay over the real outbox table, but sending through a fake bus. */
  private OutboxRelay relayOver(MessageBus bus) {
    return new OutboxRelay(repository, bus, BATCH_SIZE, SHORT_SEND_TIMEOUT);
  }

  /** A bus that acknowledges every message at once, except the one with this key. */
  private static MessageBus acknowledgingAllBut(String key, CompletableFuture<Void> outcome) {
    return message -> key.equals(message.key()) ? outcome : CompletableFuture.completedFuture(null);
  }

  private static String newKey() {
    return UUID.randomUUID().toString();
  }

  /** The topics the tests publish to, created when the context starts. */
  @TestConfiguration(proxyBeanMethods = false)
  static class Topics {

    @Bean
    NewTopic firstTopic() {
      return TopicBuilder.name(FIRST).partitions(2).build();
    }

    @Bean
    NewTopic secondTopic() {
      return TopicBuilder.name(SECOND).partitions(1).build();
    }
  }
}
