package com.shashank.invoicekit.invoice.adapter.outbound.postgres;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.shashank.invoicekit.invoice.domain.IntakeFixtures;
import com.shashank.invoicekit.invoice.domain.entity.AdapterConfig;
import com.shashank.invoicekit.invoice.domain.entity.BusinessUnitEntity;
import com.shashank.invoicekit.invoice.domain.entity.GstState;
import com.shashank.invoicekit.invoice.domain.entity.InvoiceField;
import com.shashank.invoicekit.invoice.domain.entity.LegalEntity;
import com.shashank.invoicekit.invoice.domain.entity.TaxRate;
import com.shashank.invoicekit.numbering.domain.entity.NumberFormat;
import com.shashank.invoicekit.support.PostgresFixture;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.EnumSet;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase.Replace;
import org.springframework.boot.test.autoconfigure.jdbc.JdbcTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Checks every reference table maps onto its record, against the migrations and the temporary test
 * series in a real PostgreSQL. Each test's rows are rolled back afterwards.
 */
@JdbcTest(properties = "spring.flyway.locations=classpath:db/migration,classpath:db/testseries")
@AutoConfigureTestDatabase(replace = Replace.NONE)
@Import(PostgresFixture.class)
class PostgresReferenceDataTest {

  private static final LocalDate INVOICE_DATE = LocalDate.of(2026, 11, 1);

  @Autowired private JdbcClient jdbcClient;

  private PostgresReferenceData referenceData;

  @BeforeEach
  void createReferenceData() {
    referenceData = new PostgresReferenceData(jdbcClient, IntakeFixtures.JSON);
  }

  @Test
  void readsEveryEntityColumnWithItsStateName() {
    assertThat(referenceData.entity("TEST_INTAKE"))
        .contains(
            new LegalEntity(
                "TEST_INTAKE",
                "Fake Intake Test Entity",
                "Fake Intake",
                "27BBBBB1111B1Z5",
                "Maharashtra",
                "27",
                "Fake address, Mumbai 400001",
                "Fake registered office, Mumbai 400001",
                "FAKE-INTAKE-CIN",
                "BBBBB1111B",
                "AD2799999999999",
                "Fake Signatory",
                Map.of("accountName", "Fake Intake Test Entity", "ifsc", "FAKE0000001"),
                "intake@test.example",
                "+91 11111 11111",
                "Fake payment terms."));
  }

  @Test
  void readsAnEntityWithoutBankDetailsAsEmpty() {
    assertThat(referenceData.entity("TEST_NUMBERING"))
        .hasValueSatisfying(entity -> assertThat(entity.bankDetails()).isEmpty());
  }

  @Test
  void findsNoEntityForAnUnknownCode() {
    assertThat(referenceData.entity("NO_SUCH_ENTITY")).isEmpty();
  }

  @Test
  void readsTheBusinessUnitEntityRow() {
    assertThat(referenceData.businessUnitEntity("subscriptions", "TEST_INTAKE"))
        .contains(new BusinessUnitEntity("subscriptions", "TEST_INTAKE", 1));
  }

  @Test
  void findsNoRowForAnEntityTheBusinessUnitMayNotUse() {
    assertThat(referenceData.businessUnitEntity("subscriptions", "TEST_NUMBERING")).isEmpty();
  }

  @Test
  void listsEveryBusinessUnitEntityRow() {
    assertThat(referenceData.businessUnitEntities())
        .containsExactlyInAnyOrder(
            new BusinessUnitEntity("numbering-test", "TEST_NUMBERING", 1),
            new BusinessUnitEntity("numbering-test-sharded", "TEST_NUMBERING", 4),
            new BusinessUnitEntity("subscriptions", "TEST_INTAKE", 1));
  }

  @Test
  void readsTheActiveConfigWithItsFieldsAndNumberFormat() {
    assertThat(referenceData.activeConfig("subscriptions")).contains(subscriptionsConfig());
  }

  @Test
  void treatsAnInactiveEntityAsMissing() {
    jdbcClient.sql("UPDATE entity SET active = false WHERE entity_code = 'TEST_INTAKE'").update();

    assertThat(referenceData.entity("TEST_INTAKE")).isEmpty();
  }

  @Test
  void ignoresAnInactiveConfig() {
    insertConfig("usage", "[]", false);

    assertThat(referenceData.activeConfig("usage")).isEmpty();
  }

  @Test
  void listsEveryActiveConfig() {
    insertConfig("usage", "[\"USAGE_SUMMARY\"]", true);

    assertThat(referenceData.activeConfigs())
        .containsExactlyInAnyOrder(
            subscriptionsConfig(),
            new AdapterConfig(
                "usage",
                1,
                EnumSet.of(InvoiceField.USAGE_SUMMARY),
                NumberFormat.parse("USG{fy}{seq:10}")));
  }

  @Test
  void refusesAConfigNamingAnUnknownField() {
    insertConfig("usage", "[\"PLAN_NAME\", \"SHOE_SIZE\"]", true);

    assertThatThrownBy(referenceData::activeConfigs)
        .isInstanceOf(IllegalStateException.class)
        .hasMessage(
            "adapter_config for business unit usage version 1 names unknown field SHOE_SIZE");
  }

  @Test
  void refusesAllowedFieldsThatAreNotAList() {
    insertConfig("usage", "{\"PLAN_NAME\": true}", true);

    assertThatThrownBy(referenceData::activeConfigs)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("JSON of the wrong shape");
  }

  @Test
  void readsAGstState() {
    assertThat(referenceData.gstState("29")).contains(new GstState("29", "Karnataka"));
  }

  @Test
  void findsNoStateForAnUnknownCode() {
    assertThat(referenceData.gstState("99")).isEmpty();
  }

  @Test
  void readsTheSeededTaxRate() {
    assertThat(referenceData.taxRate("SAAS_18", INVOICE_DATE))
        .contains(
            new TaxRate(
                "SAAS_18",
                "998314",
                new BigDecimal("18.00"),
                new BigDecimal("0.00"),
                IntakeFixtures.GST_START,
                null));
  }

  @Test
  void readsTheTaxRateInForceOnEachDate() {
    insertTaxRate("10.00", LocalDate.of(2020, 1, 1), LocalDate.of(2026, 12, 31));
    insertTaxRate("12.00", LocalDate.of(2027, 1, 1), null);

    assertThat(referenceData.taxRate("TEST_RATE", LocalDate.of(2026, 12, 31)))
        .map(TaxRate::gstRate)
        .contains(new BigDecimal("10.00"));
    assertThat(referenceData.taxRate("TEST_RATE", LocalDate.of(2027, 1, 1)))
        .map(TaxRate::gstRate)
        .contains(new BigDecimal("12.00"));
  }

  @Test
  void findsNoTaxRateBeforeTheCodeTakesEffect() {
    assertThat(referenceData.taxRate("SAAS_18", LocalDate.of(2017, 6, 30))).isEmpty();
  }

  @Test
  void readsEveryDeclarationWithItsConditions() {
    assertThat(referenceData.declarations())
        .containsExactlyInAnyOrderElementsOf(IntakeFixtures.seedDeclarations());
  }

  /** The seeded subscriptions config, with every optional field V4 allows. */
  private static AdapterConfig subscriptionsConfig() {
    return new AdapterConfig(
        "subscriptions",
        1,
        EnumSet.of(
            InvoiceField.DUE_DATE,
            InvoiceField.PAYMENT_TERMS,
            InvoiceField.PO_NUMBER,
            InvoiceField.SUBSCRIPTION_ID,
            InvoiceField.PLAN_NAME,
            InvoiceField.BILLING_CYCLE,
            InvoiceField.COUPON_CODE),
        NumberFormat.parse("SUB{shard}{fy}{seq:10}"));
  }

  private void insertConfig(String businessUnit, String allowedFields, boolean active) {
    jdbcClient
        .sql(
            """
            INSERT INTO adapter_config (bu, version, allowed_fields, number_format, active)
            VALUES (?, 1, CAST(? AS jsonb), 'USG{fy}{seq:10}', ?)
            """)
        .params(businessUnit, allowedFields, active)
        .update();
  }

  private void insertTaxRate(String gstRate, LocalDate from, LocalDate to) {
    jdbcClient
        .sql(
            """
            INSERT INTO tax_code (tax_code, hsn_code, gst_rate, effective_from, effective_to)
            VALUES ('TEST_RATE', '999999', CAST(? AS numeric), ?, ?)
            """)
        .params(gstRate, from, to)
        .update();
  }
}
