package com.shashank.invoicekit.invoice.adapter.outbound.postgres;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shashank.invoicekit.invoice.application.port.outbound.ReferenceData;
import com.shashank.invoicekit.invoice.domain.entity.AdapterConfig;
import com.shashank.invoicekit.invoice.domain.entity.BusinessUnitEntity;
import com.shashank.invoicekit.invoice.domain.entity.Declaration;
import com.shashank.invoicekit.invoice.domain.entity.GstState;
import com.shashank.invoicekit.invoice.domain.entity.InvoiceField;
import com.shashank.invoicekit.invoice.domain.entity.LegalEntity;
import com.shashank.invoicekit.invoice.domain.entity.SupplyCategory;
import com.shashank.invoicekit.invoice.domain.entity.TaxRate;
import com.shashank.invoicekit.numbering.domain.entity.NumberFormat;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Reads reference data straight from PostgreSQL on every call. Wrap it in {@link
 * CachedReferenceData} so events don't query the database for data that rarely changes.
 *
 * <p>Adapter configs are fully parsed here, so an unknown field name or a broken number format
 * fails when the startup check first loads them, not when an event arrives.
 */
public class PostgresReferenceData implements ReferenceData {

  /**
   * The entity with its state's name, which every payload prints next to the state code. An
   * inactive entity counts as missing, so it can no longer issue invoices.
   */
  private static final String ENTITY =
      """
      SELECT entity.*, gst_state.state_name
      FROM entity
      JOIN gst_state USING (state_code)
      WHERE entity_code = :entityCode AND active
      """;

  private static final String BUSINESS_UNIT_ENTITIES =
      "SELECT bu, entity_code, number_shards FROM bu_entity";

  private static final String BUSINESS_UNIT_ENTITY =
      BUSINESS_UNIT_ENTITIES + " WHERE bu = :businessUnit AND entity_code = :entityCode";

  private static final String ACTIVE_CONFIGS =
      "SELECT bu, version, allowed_fields, number_format FROM adapter_config WHERE active";

  private static final String ACTIVE_CONFIG = ACTIVE_CONFIGS + " AND bu = :businessUnit";

  private static final String GST_STATE =
      "SELECT state_code, state_name FROM gst_state WHERE state_code = :stateCode";

  /** The one row whose dates include the day. Overlapping rows are bad data and fail loudly. */
  private static final String TAX_RATE =
      """
      SELECT tax_code, hsn_code, gst_rate, cess_rate, effective_from, effective_to
      FROM tax_code
      WHERE tax_code = :taxCode
        AND effective_from <= :date
        AND (effective_to IS NULL OR effective_to >= :date)
      """;

  private static final String DECLARATIONS =
      """
      SELECT code, body, bu, supply_category, reverse_charge, recipient_registered, sort_order,
             effective_from, effective_to
      FROM declaration
      """;

  /** Bank details keep the order the database returns them in. */
  private static final TypeReference<LinkedHashMap<String, String>> TEXT_MAP =
      new TypeReference<>() {};

  private static final TypeReference<String[]> TEXT_ARRAY = new TypeReference<>() {};

  private final JdbcClient jdbcClient;
  private final ObjectMapper objectMapper;

  public PostgresReferenceData(JdbcClient jdbcClient, ObjectMapper objectMapper) {
    this.jdbcClient = jdbcClient;
    this.objectMapper = objectMapper;
  }

  @Override
  public Optional<LegalEntity> entity(String entityCode) {
    return jdbcClient
        .sql(ENTITY)
        .param("entityCode", entityCode)
        .query((row, index) -> legalEntity(row))
        .optional();
  }

  @Override
  public Optional<BusinessUnitEntity> businessUnitEntity(String businessUnit, String entityCode) {
    return jdbcClient
        .sql(BUSINESS_UNIT_ENTITY)
        .param("businessUnit", businessUnit)
        .param("entityCode", entityCode)
        .query((row, index) -> businessUnitEntity(row))
        .optional();
  }

  @Override
  public Optional<AdapterConfig> activeConfig(String businessUnit) {
    return jdbcClient
        .sql(ACTIVE_CONFIG)
        .param("businessUnit", businessUnit)
        .query((row, index) -> adapterConfig(row))
        .optional();
  }

  @Override
  public Optional<GstState> gstState(String stateCode) {
    return jdbcClient
        .sql(GST_STATE)
        .param("stateCode", stateCode)
        .query(
            (row, index) -> new GstState(row.getString("state_code"), row.getString("state_name")))
        .optional();
  }

  @Override
  public Optional<TaxRate> taxRate(String taxCode, LocalDate date) {
    return jdbcClient
        .sql(TAX_RATE)
        .param("taxCode", taxCode)
        .param("date", date)
        .query((row, index) -> taxRate(row))
        .optional();
  }

  @Override
  public List<Declaration> declarations() {
    return jdbcClient.sql(DECLARATIONS).query((row, index) -> declaration(row)).list();
  }

  @Override
  public List<AdapterConfig> activeConfigs() {
    return jdbcClient.sql(ACTIVE_CONFIGS).query((row, index) -> adapterConfig(row)).list();
  }

  @Override
  public List<BusinessUnitEntity> businessUnitEntities() {
    return jdbcClient
        .sql(BUSINESS_UNIT_ENTITIES)
        .query((row, index) -> businessUnitEntity(row))
        .list();
  }

  private LegalEntity legalEntity(ResultSet row) throws SQLException {
    String bankDetails = row.getString("bank_details");
    return new LegalEntity(
        row.getString("entity_code"),
        row.getString("legal_name"),
        row.getString("trade_name"),
        row.getString("gstin"),
        row.getString("state_name"),
        row.getString("state_code"),
        row.getString("address"),
        row.getString("registered_office_address"),
        row.getString("cin"),
        row.getString("pan"),
        row.getString("lut_number"),
        row.getString("authorised_signatory"),
        bankDetails == null ? null : readJson(bankDetails, TEXT_MAP),
        row.getString("contact_email"),
        row.getString("contact_phone"),
        row.getString("terms"));
  }

  private static BusinessUnitEntity businessUnitEntity(ResultSet row) throws SQLException {
    return new BusinessUnitEntity(
        row.getString("bu"), row.getString("entity_code"), row.getInt("number_shards"));
  }

  private AdapterConfig adapterConfig(ResultSet row) throws SQLException {
    String businessUnit = row.getString("bu");
    int version = row.getInt("version");
    String[] fieldNames = readJson(row.getString("allowed_fields"), TEXT_ARRAY);
    Set<InvoiceField> allowedFields =
        Arrays.stream(fieldNames)
            .map(name -> invoiceField(name, businessUnit, version))
            .collect(Collectors.toUnmodifiableSet());
    return new AdapterConfig(
        businessUnit, version, allowedFields, NumberFormat.parse(row.getString("number_format")));
  }

  /** A field the code doesn't know means the config and the code disagree, so intake stops. */
  private static InvoiceField invoiceField(String name, String businessUnit, int version) {
    try {
      return InvoiceField.valueOf(name);
    } catch (IllegalArgumentException unknown) {
      throw new IllegalStateException(
          "adapter_config for business unit "
              + businessUnit
              + " version "
              + version
              + " names unknown field "
              + name,
          unknown);
    }
  }

  private static TaxRate taxRate(ResultSet row) throws SQLException {
    return new TaxRate(
        row.getString("tax_code"),
        row.getString("hsn_code"),
        row.getBigDecimal("gst_rate"),
        row.getBigDecimal("cess_rate"),
        row.getObject("effective_from", LocalDate.class),
        row.getObject("effective_to", LocalDate.class));
  }

  private static Declaration declaration(ResultSet row) throws SQLException {
    String supplyCategory = row.getString("supply_category");
    return new Declaration(
        row.getString("code"),
        row.getString("body"),
        row.getString("bu"),
        supplyCategory == null ? null : SupplyCategory.valueOf(supplyCategory),
        row.getObject("reverse_charge", Boolean.class),
        row.getObject("recipient_registered", Boolean.class),
        row.getInt("sort_order"),
        row.getObject("effective_from", LocalDate.class),
        row.getObject("effective_to", LocalDate.class));
  }

  /** Reads a JSON column. The database checks it is JSON, so a failure here is a real fault. */
  private <T> T readJson(String json, TypeReference<T> type) {
    try {
      return objectMapper.readValue(json, type);
    } catch (JsonProcessingException error) {
      throw new IllegalStateException(
          "Reference data holds JSON of the wrong shape: " + json, error);
    }
  }
}
