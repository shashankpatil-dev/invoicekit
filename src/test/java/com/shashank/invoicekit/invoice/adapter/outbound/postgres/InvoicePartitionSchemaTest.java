package com.shashank.invoicekit.invoice.adapter.outbound.postgres;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.shashank.invoicekit.numbering.domain.entity.FinancialYear;
import com.shashank.invoicekit.support.PostgresFixture;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase.Replace;
import org.springframework.boot.test.autoconfigure.jdbc.JdbcTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Checks the partitioned invoice tables and the reference data in a real PostgreSQL: invoices land
 * in their year's partition, invoice_event catches repeated events, and next year's partition
 * exists before it is needed.
 *
 * <p>Each test runs in a transaction that is rolled back afterwards, so tests never see each
 * other's rows.
 */
@JdbcTest(properties = "spring.flyway.locations=classpath:db/migration,classpath:db/testseries")
@AutoConfigureTestDatabase(replace = Replace.NONE)
@Import(PostgresFixture.class)
class InvoicePartitionSchemaTest {

  private static final String ENTITY_CODE = "TEST_NUMBERING";
  private static final String BUSINESS_UNIT = "subscriptions";
  private static final String OTHER_BUSINESS_UNIT = "numbering-test";
  private static final String FINANCIAL_YEAR = "2026-27";

  @Autowired private JdbcClient jdbcClient;

  @ParameterizedTest
  @ValueSource(strings = {"2025-26", "2026-27", "2030-31"})
  void invoiceLandsInItsFinancialYearPartition(String financialYear) {
    UUID invoiceId = insertInvoice(financialYear);

    assertThat(partitionHolding(invoiceId)).isEqualTo(partitionName(financialYear));
  }

  @Test
  void rejectsInvoiceForAYearWithoutAPartition() {
    assertThatThrownBy(() -> insertInvoice("2031-32"))
        .isInstanceOf(DataIntegrityViolationException.class)
        .hasMessageContaining("no partition of relation \"invoice\"");
  }

  @Test
  void rejectsTheSameEventTwiceEvenForAnotherInvoiceAndYear() {
    insertEvent(BUSINESS_UNIT, "event-1", UUID.randomUUID(), "2026-27");

    // PostgreSQL names the violated key after the hash partition's index, so match its columns.
    assertThatThrownBy(() -> insertEvent(BUSINESS_UNIT, "event-1", UUID.randomUUID(), "2027-28"))
        .isInstanceOf(DuplicateKeyException.class)
        .hasMessageContaining("Key (bu, source_event_id)=(subscriptions, event-1) already exists");
  }

  @Test
  void allowsTheSameEventIdUnderTwoBusinessUnits() {
    insertEvent(BUSINESS_UNIT, "event-1", UUID.randomUUID(), FINANCIAL_YEAR);
    insertEvent(OTHER_BUSINESS_UNIT, "event-1", UUID.randomUUID(), FINANCIAL_YEAR);

    assertThat(count("SELECT count(*) FROM invoice_event WHERE source_event_id = 'event-1'"))
        .isEqualTo(2);
  }

  @Test
  void nextFinancialYearAlreadyHasAPartition() {
    String nextYear = FinancialYear.of(LocalDate.now().plusYears(1)).label();
    String partition = partitionName(nextYear);

    assertThat(tableExists(partition))
        .as(
            "invoice has no partition for %s. Add a migration that creates %s before 1 April.",
            nextYear, partition)
        .isTrue();
  }

  @Test
  void storesOutboxAndAuditRowsForAnInvoice() {
    UUID invoiceId = insertInvoice(FINANCIAL_YEAR);

    jdbcClient
        .sql(
            """
            INSERT INTO outbox (id, invoice_id, topic, message_key, body)
            VALUES (?, ?, 'invoice.render', ?, '{}')
            """)
        .params(UUID.randomUUID(), invoiceId, invoiceId.toString())
        .update();
    jdbcClient
        .sql("INSERT INTO invoice_audit (invoice_id, to_status, actor) VALUES (?, 'NUMBERED', ?)")
        .params(invoiceId, "intake")
        .update();

    assertThat(count("SELECT count(*) FROM outbox WHERE invoice_id = ?", invoiceId)).isEqualTo(1);
    assertThat(count("SELECT count(*) FROM invoice_audit WHERE invoice_id = ?", invoiceId))
        .isEqualTo(1);
  }

  @Test
  void seedsMaharashtraStateCode() {
    assertThat(single("SELECT state_name FROM gst_state WHERE state_code = '27'", String.class))
        .isEqualTo("Maharashtra");
  }

  @Test
  void seedsSaasTaxCodeAtEighteenPercent() {
    assertThat(single("SELECT gst_rate FROM tax_code WHERE tax_code = 'SAAS_18'", BigDecimal.class))
        .isEqualByComparingTo("18.00");
  }

  @Test
  void seedsTheActiveSubscriptionsNumberFormat() {
    assertThat(
            single(
                "SELECT number_format FROM adapter_config WHERE bu = 'subscriptions' AND active",
                String.class))
        .isEqualTo("SUB{shard}{fy}{seq:10}");
  }

  @Test
  void seedsTheSubscriptionsAllowedFields() {
    assertThat(
            jdbcClient
                .sql(
                    """
                    SELECT jsonb_array_elements_text(allowed_fields) FROM adapter_config
                    WHERE bu = 'subscriptions' AND active
                    """)
                .query(String.class)
                .list())
        .containsExactly(
            "DUE_DATE",
            "PAYMENT_TERMS",
            "PO_NUMBER",
            "SUBSCRIPTION_ID",
            "PLAN_NAME",
            "BILLING_CYCLE",
            "COUPON_CODE");
  }

  /** The partition name V3 gives a year, such as {@code invoice_2026_27} for 2026-27. */
  private static String partitionName(String financialYear) {
    return "invoice_" + financialYear.replace('-', '_');
  }

  /** Adds an unnumbered invoice for the year and returns its ID. */
  private UUID insertInvoice(String financialYear) {
    UUID id = UUID.randomUUID();
    jdbcClient
        .sql(
            """
            INSERT INTO invoice (id, bu, entity_code, source_event_id, financial_year,
                                 invoice_date, contract_version, payload, status)
            VALUES (:id, :businessUnit, :entityCode, :sourceEventId, :financialYear,
                    DATE '2026-11-04', 1, '{}', 'NUMBERED')
            """)
        .param("id", id)
        .param("businessUnit", BUSINESS_UNIT)
        .param("entityCode", ENTITY_CODE)
        .param("sourceEventId", "event-" + id)
        .param("financialYear", financialYear)
        .update();
    return id;
  }

  private void insertEvent(
      String businessUnit, String sourceEventId, UUID invoiceId, String financialYear) {
    jdbcClient
        .sql(
            """
            INSERT INTO invoice_event (bu, source_event_id, invoice_id, financial_year)
            VALUES (?, ?, ?, ?)
            """)
        .params(businessUnit, sourceEventId, invoiceId, financialYear)
        .update();
  }

  private String partitionHolding(UUID invoiceId) {
    return single(
        "SELECT tableoid::regclass::text FROM invoice WHERE id = ?", String.class, invoiceId);
  }

  private boolean tableExists(String table) {
    return single("SELECT to_regclass(?) IS NOT NULL", Boolean.class, table);
  }

  private int count(String sql, Object... params) {
    return single(sql, Integer.class, params);
  }

  private <T> T single(String sql, Class<T> type, Object... params) {
    return jdbcClient.sql(sql).params(params).query(type).single();
  }
}
