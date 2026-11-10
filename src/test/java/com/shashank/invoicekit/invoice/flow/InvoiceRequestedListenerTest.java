package com.shashank.invoicekit.invoice.flow;

import static com.shashank.invoicekit.invoice.flow.IntakeEvents.BUSINESS_UNIT;
import static com.shashank.invoicekit.invoice.flow.IntakeEvents.ENTITY_CODE;
import static com.shashank.invoicekit.invoice.flow.IntakeEvents.FINANCIAL_YEAR;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.dockerjava.api.DockerClient;
import com.shashank.invoicekit.invoice.adapter.kafka.RejectionHeaders;
import com.shashank.invoicekit.invoice.domain.exception.RejectionCode;
import com.shashank.invoicekit.invoice.flow.IntakeRows.OutboxRow;
import com.shashank.invoicekit.invoice.flow.IntakeRows.SavedInvoice;
import com.shashank.invoicekit.support.KafkaFixture;
import com.shashank.invoicekit.support.PostgresFixture;
import com.shashank.invoicekit.support.SeriesReconciliation;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;
import java.util.function.Consumer;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Sends events through a real Kafka broker to the intake worker and checks what comes out: a
 * numbered invoice, one invoice for a repeated event, a dead letter with its headers for each kind
 * of rejection, and no lost event while the database is down. See docs/INTAKE.md section 8.
 *
 * <p>The whole application runs in the {@code intake} profile, so the listener, its error handler
 * and the Kafka settings are the ones production uses. Intake commits for real, so each test
 * deletes its rows afterwards. All tests share one context, one broker and one database.
 */
@SpringBootTest(
    webEnvironment = WebEnvironment.NONE,
    properties = {
      "spring.flyway.locations=classpath:db/migration,classpath:db/testseries",
      // Give up on the database within 2 seconds, whether getting, checking or reading from a
      // connection, so a paused database fails events instead of only slowing them down.
      "spring.datasource.hikari.connection-timeout=1000",
      "spring.datasource.hikari.validation-timeout=500",
      "spring.datasource.hikari.data-source-properties.socketTimeout=2"
    })
@ActiveProfiles("intake")
@Import({
  PostgresFixture.class,
  KafkaFixture.class,
  InvoiceRequestedListenerTest.Topics.class,
  IntakeTopics.class,
  IntakeRows.class,
  SeriesReconciliation.class
})
@ExtendWith(OutputCaptureExtension.class)
class InvoiceRequestedListenerTest {

  private static final Consumer<ObjectNode> NO_CHANGE = event -> {};

  private static final int EVENTS_DURING_OUTAGE = 8;
  private static final Duration OUTAGE = Duration.ofSeconds(3);

  /** Longest wait for the outage's events once the database is back, well above the retry waits. */
  private static final Duration RECOVERY = Duration.ofSeconds(60);

  @Autowired private IntakeTopics topics;
  @Autowired private IntakeRows rows;
  @Autowired private SeriesReconciliation reconciliation;
  @Autowired private PostgreSQLContainer<?> postgres;

  @AfterEach
  void deleteRows() {
    rows.deleteAll();
  }

  @Test
  void validEventBecomesANumberedInvoiceWithItsOutboxRow() {
    String sourceEventId = newSourceEventId();

    topics.awaitAcknowledged(sendEvent(sourceEventId, NO_CHANGE));

    SavedInvoice invoice = rows.invoice(sourceEventId);
    assertThat(invoice.invoiceNumber()).isEqualTo(IntakeEvents.number(1));
    assertThat(invoice.status()).isEqualTo("NUMBERED");
    assertThat(rows.outbox(invoice.id()))
        .singleElement()
        .extracting(OutboxRow::topic)
        .isEqualTo("invoice.render");
  }

  @Test
  void sameEventSentTwiceGivesOneInvoice() {
    String sourceEventId = newSourceEventId();

    sendEvent(sourceEventId, NO_CHANGE);
    topics.awaitAcknowledged(sendEvent(sourceEventId, NO_CHANGE));

    assertThat(rows.count("invoice")).isEqualTo(1);
    assertThat(rows.count("outbox")).isEqualTo(1);
    assertThat(reconciliation.issuedCount(ENTITY_CODE, BUSINESS_UNIT, FINANCIAL_YEAR)).isEqualTo(1);
  }

  /** Broken JSON, JSON null, and a message with no value at all. */
  @ParameterizedTest
  @NullSource
  @ValueSource(strings = {"{\"bu\": \"subscriptions\",", "null"})
  void unreadableMessageIsDeadLetteredAsAnInvalidMessage(String message) {
    String sourceEventId = newSourceEventId();

    ConsumerRecord<String, String> deadLetter =
        awaitDeadLettered(sourceEventId, topics.send(sourceEventId, message));

    assertThat(header(deadLetter, RejectionHeaders.ERROR_CODE)).isEqualTo("INVALID_MESSAGE");
    assertThat(deadLetter.value()).isEqualTo(message);
  }

  @Test
  void eventBreakingContractRulesIsDeadLetteredWithEveryReason() {
    String sourceEventId = newSourceEventId();

    ConsumerRecord<String, String> deadLetter =
        awaitDeadLettered(
            sourceEventId,
            sendEvent(
                sourceEventId,
                event -> {
                  event.put("schemaVersion", 1);
                  event.remove("currency");
                }));

    assertThat(header(deadLetter, RejectionHeaders.ERROR_CODE))
        .isEqualTo(RejectionCode.VALIDATION_FAILED.name());
    assertThat(header(deadLetter, RejectionHeaders.ERROR_MESSAGE))
        .isEqualTo("schemaVersion must be 2, but was 1; currency is required");
  }

  @Test
  void eventFromAnEntityTheBusinessUnitMayNotUseIsDeadLetteredAsNotAllowed() {
    String sourceEventId = newSourceEventId();

    ConsumerRecord<String, String> deadLetter =
        awaitDeadLettered(
            sourceEventId,
            sendEvent(sourceEventId, event -> event.put("entityCode", "TEST_NUMBERING")));

    assertThat(header(deadLetter, RejectionHeaders.ERROR_CODE))
        .isEqualTo(RejectionCode.ENTITY_NOT_ALLOWED.name());
    assertThat(header(deadLetter, RejectionHeaders.ERROR_MESSAGE))
        .isEqualTo("business unit subscriptions may not invoice from entity TEST_NUMBERING");
  }

  /**
   * Intake logs "failed on attempt" for every failure. Events that failed and still became invoices
   * were retried, and none was dead-lettered.
   */
  @Test
  void eventsSentWhileTheDatabaseIsPausedAreRetriedUntilEachIsAnInvoice(CapturedOutput output)
      throws Exception {
    long deadLettersBefore = topics.deadLetterCount();

    sendEventsWhileTheDatabaseIsPaused();

    await().atMost(RECOVERY).until(() -> rows.count("invoice") == EVENTS_DURING_OUTAGE);
    assertThat(output).contains("failed on attempt");
    assertThat(topics.deadLetterCount()).isEqualTo(deadLettersBefore);
    assertThat(reconciliation.problems()).isEmpty();
  }

  private RecordMetadata sendEvent(String sourceEventId, Consumer<ObjectNode> change) {
    return topics.send(sourceEventId, IntakeEvents.json(sourceEventId, change));
  }

  /**
   * Waits for a message's dead letter and checks the parts every dead letter has: the source key,
   * Spring Kafka's headers naming where the message came from, and a committed source offset so the
   * message is not read again.
   */
  private ConsumerRecord<String, String> awaitDeadLettered(
      String sourceEventId, RecordMetadata sent) {
    ConsumerRecord<String, String> deadLetter =
        topics.awaitDeadLetter(IntakeTopics.key(sourceEventId));

    assertThat(header(deadLetter, KafkaHeaders.DLT_ORIGINAL_TOPIC))
        .isEqualTo(IntakeTopics.REQUESTED);
    assertThat(binaryHeader(deadLetter, KafkaHeaders.DLT_ORIGINAL_PARTITION).getInt())
        .isEqualTo(sent.partition());
    assertThat(binaryHeader(deadLetter, KafkaHeaders.DLT_ORIGINAL_OFFSET).getLong())
        .isEqualTo(sent.offset());
    topics.awaitAcknowledged(sent);
    return deadLetter;
  }

  /** Freezes the test database's container while events are sent, then always thaws it. */
  private void sendEventsWhileTheDatabaseIsPaused() throws InterruptedException {
    DockerClient docker = DockerClientFactory.instance().client();
    String containerId = postgres.getContainerId();
    docker.pauseContainerCmd(containerId).exec();
    try {
      for (int event = 0; event < EVENTS_DURING_OUTAGE; event++) {
        sendEvent(newSourceEventId(), NO_CHANGE);
      }
      Thread.sleep(OUTAGE);
    } finally {
      docker.unpauseContainerCmd(containerId).exec();
    }
  }

  private static String newSourceEventId() {
    return "listener-" + UUID.randomUUID();
  }

  private static String header(ConsumerRecord<String, String> message, String name) {
    return new String(message.headers().lastHeader(name).value(), StandardCharsets.UTF_8);
  }

  /** Spring Kafka writes the source partition and offset as raw numbers, not text. */
  private static ByteBuffer binaryHeader(ConsumerRecord<String, String> message, String name) {
    return ByteBuffer.wrap(message.headers().lastHeader(name).value());
  }

  /**
   * Creates intake's two topics before the listener starts. The dead-letter topic has fewer
   * partitions, as in production, so a dead letter only arrives if Kafka picks its partition.
   */
  @TestConfiguration(proxyBeanMethods = false)
  static class Topics {

    @Bean
    NewTopic requestedTopic() {
      return TopicBuilder.name(IntakeTopics.REQUESTED).partitions(4).build();
    }

    @Bean
    NewTopic deadLetterTopic() {
      return TopicBuilder.name(IntakeTopics.DEAD_LETTERS).partitions(2).build();
    }
  }
}
