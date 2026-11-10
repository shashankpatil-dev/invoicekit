package com.shashank.invoicekit.invoice.domain;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.shashank.invoicekit.invoice.domain.entity.AdapterConfig;
import com.shashank.invoicekit.invoice.domain.entity.Declaration;
import com.shashank.invoicekit.invoice.domain.entity.GstState;
import com.shashank.invoicekit.invoice.domain.entity.InvoiceContext;
import com.shashank.invoicekit.invoice.domain.entity.InvoiceDraft;
import com.shashank.invoicekit.invoice.domain.entity.InvoiceField;
import com.shashank.invoicekit.invoice.domain.entity.InvoiceRequested;
import com.shashank.invoicekit.invoice.domain.entity.LegalEntity;
import com.shashank.invoicekit.invoice.domain.entity.SupplyCategory;
import com.shashank.invoicekit.invoice.domain.entity.TaxRate;
import com.shashank.invoicekit.numbering.domain.entity.NumberFormat;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * The Invoice Superset examples and the reference data behind them, shared by the intake tests. All
 * names, identifiers and rates are made up, as in the superset.
 */
public final class IntakeFixtures {

  /** Reads and writes JSON the way Spring Boot's mapper does: dates as ISO text. */
  public static final ObjectMapper JSON =
      JsonMapper.builder()
          .addModule(new JavaTimeModule())
          .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
          .build();

  public static final String DOMESTIC_EVENT = "subscriptions-domestic-event.json";
  public static final String DOMESTIC_PAYLOAD = "subscriptions-domestic-payload.json";
  public static final String EXPORT_EVENT = "international-export-event.json";
  public static final String EXPORT_PAYLOAD = "international-export-payload.json";

  public static final LocalDate GST_START = LocalDate.of(2017, 7, 1);
  public static final GstState MAHARASHTRA = new GstState("27", "Maharashtra");
  public static final GstState KARNATAKA = new GstState("29", "Karnataka");
  public static final TaxRate SAAS_18 =
      new TaxRate("SAAS_18", "998314", new BigDecimal("18.00"), null, GST_START, null);

  private static final String RESOURCES = "/payloads/intake/";

  private IntakeFixtures() {}

  /** One of the example events, read as the intake listener will read it. */
  public static InvoiceRequested event(String resource) {
    return JSON.convertValue(tree(resource), InvoiceRequested.class);
  }

  /** The domestic example event with one change, for testing a single rule. */
  public static InvoiceRequested domesticEvent(Consumer<ObjectNode> change) {
    return changed(DOMESTIC_EVENT, change);
  }

  /** The export example event with one change, for testing a single rule. */
  public static InvoiceRequested exportEvent(Consumer<ObjectNode> change) {
    return changed(EXPORT_EVENT, change);
  }

  /** A draft that keeps the event's lines and attributes, as a pass-through adapter makes. */
  public static InvoiceDraft draftOf(InvoiceRequested event) {
    return new InvoiceDraft(event, event.lineItems(), event.attributes());
  }

  /** One of the example files as a JSON tree. */
  public static JsonNode tree(String resource) {
    try (InputStream json = IntakeFixtures.class.getResourceAsStream(RESOURCES + resource)) {
      return JSON.readTree(json);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /** The supplier in both examples, an entity registered in Maharashtra. */
  public static LegalEntity acmeMaharashtra() {
    return acmeMaharashtra("AD2700000000000", "Payment due as per the agreed terms.");
  }

  /** The example supplier with a different LUT number and terms, either of which may be null. */
  public static LegalEntity acmeMaharashtra(String lutNumber, String terms) {
    Map<String, String> bankDetails = new LinkedHashMap<>();
    bankDetails.put("accountName", "Acme Cloud Private Limited");
    bankDetails.put("accountNumber", "000000000000");
    bankDetails.put("ifsc", "ABCD0000001");
    return new LegalEntity(
        "ACME_MH",
        "Acme Cloud Private Limited",
        "Acme Cloud",
        "27AAAAA0000A1Z5",
        "Maharashtra",
        "27",
        "45 Tech Park, Mumbai 400001",
        "1 Corporate Park, Mumbai 400001",
        "U72900MH2020PTC000000",
        "AAAAA0000A",
        lutNumber,
        null,
        bankDetails,
        "billing@acme.example",
        "+91 00000 00000",
        terms);
  }

  /** The declarations seeded by the {@code V2} migration. */
  public static List<Declaration> seedDeclarations() {
    return List.of(
        new Declaration(
            "GENERAL",
            "We declare that this invoice shows the actual price of the services described and"
                + " that all particulars are true and correct.",
            null,
            null,
            null,
            null,
            10,
            GST_START,
            null),
        new Declaration(
            "REVERSE_CHARGE",
            "Tax on this invoice is payable by the recipient on a reverse charge basis.",
            null,
            null,
            true,
            null,
            20,
            GST_START,
            null),
        new Declaration(
            "EXPORT_LUT",
            "Supply meant for export under Letter of Undertaking without payment of integrated"
                + " tax. LUT: {lutNumber}",
            null,
            SupplyCategory.EXPORT_LUT,
            null,
            null,
            20,
            GST_START,
            null),
        new Declaration(
            "SUBSCRIPTION_PERIOD",
            "Subscription charges cover the service period shown against each line.",
            "subscriptions",
            null,
            null,
            null,
            30,
            GST_START,
            null),
        new Declaration(
            "ELECTRONIC_INVOICE",
            "This is an electronically generated invoice and does not require a signature.",
            null,
            null,
            null,
            null,
            90,
            GST_START,
            null));
  }

  /** The {@code subscriptions} config: every field its example event sends. */
  public static AdapterConfig subscriptionsConfig() {
    return new AdapterConfig(
        "subscriptions",
        1,
        EnumSet.of(
            InvoiceField.DUE_DATE,
            InvoiceField.PAYMENT_TERMS,
            InvoiceField.SUBSCRIPTION_ID,
            InvoiceField.PLAN_NAME,
            InvoiceField.BILLING_CYCLE,
            InvoiceField.COUPON_CODE),
        NumberFormat.parse("SUB{shard}{fy}{seq:10}"));
  }

  /** Reference data for an invoice from the Maharashtra entity to a place of supply. */
  public static InvoiceContext context(GstState placeOfSupplyState) {
    return new InvoiceContext(
        acmeMaharashtra(),
        subscriptionsConfig(),
        placeOfSupplyState,
        Map.of(SAAS_18.taxCode(), SAAS_18),
        seedDeclarations());
  }

  private static InvoiceRequested changed(String resource, Consumer<ObjectNode> change) {
    ObjectNode event = (ObjectNode) tree(resource);
    change.accept(event);
    return JSON.convertValue(event, InvoiceRequested.class);
  }
}
