package com.shashank.invoicekit.numbering.adapter.outbound.postgres;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.shashank.invoicekit.support.PostgresFixture;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase.Replace;
import org.springframework.boot.test.autoconfigure.jdbc.JdbcTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Checks the numbering tables in a real PostgreSQL: the counter upsert works, and the constraints
 * reject numbers that break the series rules.
 *
 * <p>Each test runs in a transaction that is rolled back afterwards, so tests never see each
 * other's rows.
 */
@JdbcTest(properties = "spring.flyway.locations=classpath:db/migration,classpath:db/testseries")
@AutoConfigureTestDatabase(replace = Replace.NONE)
@Import(PostgresFixture.class)
class NumberingSchemaTest {

  private static final String ENTITY_CODE = "TEST_NUMBERING";
  private static final String BUSINESS_UNIT = "numbering-test-sharded";
  private static final String FINANCIAL_YEAR = "2026-27";

  @Autowired private JdbcClient jdbcClient;

  @Value("${invoicekit.database.idle-in-transaction-timeout}")
  private String idleInTransactionTimeout;

  @Test
  void loadsTheTemporaryTestSeries() {
    assertThat(
            jdbcClient
                .sql("SELECT bu, number_shards FROM bu_entity WHERE entity_code = ?")
                .param(ENTITY_CODE)
                .query()
                .listOfRows())
        .containsExactlyInAnyOrder(
            Map.of("bu", "numbering-test", "number_shards", 1),
            Map.of("bu", "numbering-test-sharded", "number_shards", 4));
  }

  @Test
  void appliesTheIdleInTransactionTimeoutToEveryConnection() {
    assertThat(
            jdbcClient.sql("SHOW idle_in_transaction_session_timeout").query(String.class).single())
        .isEqualTo(idleInTransactionTimeout);
  }

  @Test
  void counterStartsAtOneAndCountsUp() {
    assertThat(nextCounterValue("A")).isEqualTo(1);
    assertThat(nextCounterValue("A")).isEqualTo(2);
  }

  @Test
  void acceptsAnInvoiceBeforeItIsNumbered() {
    insertInvoice(null, null, null);

    assertThat(invoiceCount()).isEqualTo(1);
  }

  @Test
  void allowsTheSameSequenceInDifferentShards() {
    insertInvoice("A", 1L, "SUBA260000000001");
    insertInvoice("B", 1L, "SUBB260000000001");

    assertThat(invoiceCount()).isEqualTo(2);
  }

  @Test
  void rejectsTheSameSequenceTwiceInOneSeries() {
    insertInvoice("A", 1L, "SUBA260000000001");

    // PostgreSQL names the violated key after the yearly partition's index, so match its columns.
    assertThatThrownBy(() -> insertInvoice("A", 1L, "SUBA260000000002"))
        .isInstanceOf(DuplicateKeyException.class)
        .hasMessageContaining("Key (entity_code, bu, financial_year, shard, sequence_no)");
  }

  @ParameterizedTest
  @CsvSource({"a, invoice_shard_format", "AB, value too long"})
  void rejectsShardThatIsNotOneCapitalLetter(String shard, String reason) {
    assertThatThrownBy(() -> insertInvoice(shard, 1L, "SUBA260000000001"))
        .isInstanceOf(DataIntegrityViolationException.class)
        .hasMessageContaining(reason);
  }

  @Test
  void rejectsSequenceZero() {
    assertThatThrownBy(() -> insertInvoice("A", 0L, "SUBA260000000000"))
        .isInstanceOf(DataIntegrityViolationException.class)
        .hasMessageContaining("invoice_sequence_positive");
  }

  @ParameterizedTest
  @CsvSource({"SUBA2600000000001, value too long", "SUBA26 000000001, invoice_number_format"})
  void rejectsNumberThatBreaksGstRules(String invoiceNumber, String reason) {
    assertThatThrownBy(() -> insertInvoice("A", 1L, invoiceNumber))
        .isInstanceOf(DataIntegrityViolationException.class)
        .hasMessageContaining(reason);
  }

  // An empty column below is NULL, so each row leaves part of the number unset.
  @ParameterizedTest
  @CsvSource({"A, 1, ", "A, , SUBA260000000001", ", 1, SUBA260000000001"})
  void rejectsPartlyNumberedInvoice(String shard, Long sequenceNo, String invoiceNumber) {
    assertThatThrownBy(() -> insertInvoice(shard, sequenceNo, invoiceNumber))
        .isInstanceOf(DataIntegrityViolationException.class)
        .hasMessageContaining("invoice_numbered_together");
  }

  /** Runs the counter upsert that numbering uses and returns the sequence it allocated. */
  private long nextCounterValue(String shard) {
    return jdbcClient
        .sql(
            """
            INSERT INTO invoice_counter AS c (entity_code, bu, financial_year, shard, last_no)
            VALUES (?, ?, ?, ?, 1)
            ON CONFLICT (entity_code, bu, financial_year, shard)
            DO UPDATE SET last_no = c.last_no + 1
            RETURNING last_no
            """)
        .params(ENTITY_CODE, BUSINESS_UNIT, FINANCIAL_YEAR, shard)
        .query(Long.class)
        .single();
  }

  private void insertInvoice(String shard, Long sequenceNo, String invoiceNumber) {
    UUID id = UUID.randomUUID();
    jdbcClient
        .sql(
            """
            INSERT INTO invoice (id, bu, entity_code, source_event_id, financial_year,
                                 invoice_date, shard, sequence_no, invoice_number,
                                 contract_version, payload, status)
            VALUES (:id, :businessUnit, :entityCode, :sourceEventId, :financialYear,
                    DATE '2026-11-04', :shard, :sequenceNo, :invoiceNumber,
                    1, '{}', 'NUMBERED')
            """)
        .param("id", id)
        .param("businessUnit", BUSINESS_UNIT)
        .param("entityCode", ENTITY_CODE)
        .param("sourceEventId", "event-" + id)
        .param("financialYear", FINANCIAL_YEAR)
        .param("shard", shard)
        .param("sequenceNo", sequenceNo)
        .param("invoiceNumber", invoiceNumber)
        .update();
  }

  private int invoiceCount() {
    return jdbcClient
        .sql("SELECT count(*) FROM invoice WHERE entity_code = ?")
        .param(ENTITY_CODE)
        .query(Integer.class)
        .single();
  }
}
