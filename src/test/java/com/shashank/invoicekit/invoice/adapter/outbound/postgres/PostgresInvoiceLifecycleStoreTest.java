package com.shashank.invoicekit.invoice.adapter.outbound.postgres;

import static com.shashank.invoicekit.invoice.domain.IntakeFixtures.DOMESTIC_PAYLOAD;
import static com.shashank.invoicekit.invoice.domain.IntakeFixtures.JSON;
import static com.shashank.invoicekit.invoice.domain.IntakeFixtures.tree;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.shashank.invoicekit.invoice.domain.entity.InvoicePayload;
import com.shashank.invoicekit.invoice.domain.entity.InvoiceStatus;
import com.shashank.invoicekit.invoice.domain.entity.RenderJob;
import com.shashank.invoicekit.invoice.domain.entity.StoredDocument;
import com.shashank.invoicekit.numbering.domain.entity.FinancialYear;
import com.shashank.invoicekit.numbering.domain.entity.InvoiceNumber;
import com.shashank.invoicekit.numbering.domain.entity.SeriesKey;
import com.shashank.invoicekit.numbering.domain.entity.Shard;
import com.shashank.invoicekit.support.PostgresFixture;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase.Replace;
import org.springframework.boot.test.autoconfigure.jdbc.JdbcTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Checks the guarded moves out of {@code NUMBERED} in a real PostgreSQL, as docs/RENDER.md section
 * 5 describes. Each test starts from one numbered invoice, saved by intake's own store, and its
 * rows are rolled back afterwards.
 */
@JdbcTest(properties = "spring.flyway.locations=classpath:db/migration,classpath:db/testseries")
@AutoConfigureTestDatabase(replace = Replace.NONE)
@Import(PostgresFixture.class)
class PostgresInvoiceLifecycleStoreTest {

  private static final String TRACEPARENT =
      "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01";
  private static final String REASON = "template subscriptions/invoice.typ is missing";

  private static final InvoicePayload INVOICE =
      JSON.convertValue(tree(DOMESTIC_PAYLOAD), InvoicePayload.class);
  private static final InvoiceNumber NUMBER =
      new InvoiceNumber(
          new SeriesKey(
              "TEST_INTAKE",
              "subscriptions",
              FinancialYear.of(INVOICE.invoiceDate()),
              new Shard(0)),
          142,
          INVOICE.invoiceNumber());

  private static final RenderJob JOB = jobIn(INVOICE.financialYear());

  /** The same invoice under the next financial year, whose partition doesn't hold it. */
  private static final RenderJob WRONG_YEAR = jobIn("2027-28");

  private static final StoredDocument DOCUMENT =
      new StoredDocument("gs://invoices/" + JOB.documentPath().value(), "crc32c:AAAAAA==");

  private static final String INVOICE_ROW =
      "SELECT status, uri, checksum FROM invoice WHERE id = :invoiceId";

  private static final String RENDER_AUDIT =
      """
      SELECT from_status, to_status, actor, reason FROM invoice_audit
      WHERE invoice_id = :invoiceId AND actor = 'render'
      """;

  private static final String GENERATED =
      """
      SELECT id, message_key, body::text AS body, headers::text AS headers FROM outbox
      WHERE invoice_id = :invoiceId AND topic = 'invoice.generated'
      """;

  @Autowired private JdbcClient jdbcClient;

  private PostgresInvoiceLifecycleStore store;

  @BeforeEach
  void saveNumberedInvoice() {
    new PostgresInvoiceStore(jdbcClient, JSON, Map::of).saveNumbered(INVOICE, NUMBER);
    store =
        new PostgresInvoiceLifecycleStore(
            jdbcClient, JSON, () -> Map.of("traceparent", TRACEPARENT));
  }

  @Test
  void readsTheStatusOfAnInvoice() {
    assertThat(store.status(INVOICE.invoiceId(), INVOICE.financialYear()))
        .contains(InvoiceStatus.NUMBERED);
  }

  @Test
  void readsNoStatusForAnUnknownInvoice() {
    assertThat(store.status(UUID.randomUUID(), INVOICE.financialYear())).isEmpty();
  }

  @Test
  void finishSetsStoredWithTheUriAndChecksum() {
    assertThat(store.finish(JOB, DOCUMENT)).isTrue();

    assertThat(invoiceRow()).isEqualTo(new InvoiceRow("STORED", DOCUMENT.uri(), "crc32c:AAAAAA=="));
  }

  @Test
  void finishWritesOneAuditRow() {
    store.finish(JOB, DOCUMENT);

    assertThat(renderAudit()).containsExactly(new AuditRow("NUMBERED", "STORED", "render", null));
  }

  @Test
  void finishWritesOneGeneratedMessageWithTheCurrentTrace() throws JsonProcessingException {
    store.finish(JOB, DOCUMENT);

    List<GeneratedRow> generated = generated();
    assertThat(generated).hasSize(1);
    GeneratedRow message = generated.getFirst();
    assertThat(message.id().version()).isEqualTo(7);
    assertThat(message.messageKey()).isEqualTo(INVOICE.invoiceId().toString());
    assertThat(map(message.body()))
        .isEqualTo(
            Map.of(
                "schemaVersion",
                1,
                "invoiceId",
                INVOICE.invoiceId().toString(),
                "bu",
                "subscriptions",
                "invoiceNumber",
                "SUBA260000000142",
                "uri",
                DOCUMENT.uri(),
                "checksum",
                "crc32c:AAAAAA=="));
    assertThat(map(message.headers())).isEqualTo(Map.of("traceparent", TRACEPARENT));
  }

  @Test
  void secondFinishChangesNothingAndWritesNothing() {
    store.finish(JOB, DOCUMENT);

    boolean changed = store.finish(JOB, new StoredDocument("gs://invoices/other.pdf", "crc32c:B"));

    assertThat(changed).isFalse();
    assertThat(invoiceRow().uri()).isEqualTo(DOCUMENT.uri());
    assertThat(renderAudit()).hasSize(1);
    assertThat(generated()).hasSize(1);
  }

  @Test
  void finishChangesNothingOnAFailedInvoice() {
    store.markFailed(JOB, REASON);

    assertThat(store.finish(JOB, DOCUMENT)).isFalse();

    assertThat(invoiceRow()).isEqualTo(new InvoiceRow("FAILED", null, null));
    assertThat(generated()).isEmpty();
  }

  @Test
  void markFailedSetsFailedAndAuditsTheReason() {
    assertThat(store.markFailed(JOB, REASON)).isTrue();

    assertThat(invoiceRow().status()).isEqualTo("FAILED");
    assertThat(renderAudit()).containsExactly(new AuditRow("NUMBERED", "FAILED", "render", REASON));
    assertThat(generated()).isEmpty();
  }

  @Test
  void markFailedChangesNothingOnceStored() {
    store.finish(JOB, DOCUMENT);

    assertThat(store.markFailed(JOB, REASON)).isFalse();

    assertThat(invoiceRow().status()).isEqualTo("STORED");
    assertThat(renderAudit()).containsExactly(new AuditRow("NUMBERED", "STORED", "render", null));
  }

  @Test
  void statementsTouchOnlyTheInvoicesOwnPartition() {
    assertThat(store.status(INVOICE.invoiceId(), WRONG_YEAR.financialYear())).isEmpty();
    assertThat(store.finish(WRONG_YEAR, DOCUMENT)).isFalse();
    assertThat(store.markFailed(WRONG_YEAR, REASON)).isFalse();

    assertThat(invoiceRow()).isEqualTo(new InvoiceRow("NUMBERED", null, null));
    assertThat(renderAudit()).isEmpty();
    assertThat(generated()).isEmpty();
  }

  private static RenderJob jobIn(String financialYear) {
    return new RenderJob(
        INVOICE.invoiceId(),
        INVOICE.businessUnit(),
        financialYear,
        INVOICE.invoiceDate(),
        INVOICE.invoiceNumber(),
        "{}");
  }

  private InvoiceRow invoiceRow() {
    return byInvoice(INVOICE_ROW).query(InvoiceRow.class).single();
  }

  private List<AuditRow> renderAudit() {
    return byInvoice(RENDER_AUDIT).query(AuditRow.class).list();
  }

  private List<GeneratedRow> generated() {
    return byInvoice(GENERATED).query(GeneratedRow.class).list();
  }

  private JdbcClient.StatementSpec byInvoice(String sql) {
    return jdbcClient.sql(sql).param("invoiceId", INVOICE.invoiceId());
  }

  private static Map<String, Object> map(String json) throws JsonProcessingException {
    return JSON.readValue(json, new TypeReference<>() {});
  }

  record InvoiceRow(String status, String uri, String checksum) {}

  record AuditRow(String fromStatus, String toStatus, String actor, String reason) {}

  /**
   * One {@code invoice.generated} outbox row, with its body and headers as JSON text.
   *
   * @param messageKey the Kafka key
   */
  record GeneratedRow(UUID id, String messageKey, String body, String headers) {}
}
