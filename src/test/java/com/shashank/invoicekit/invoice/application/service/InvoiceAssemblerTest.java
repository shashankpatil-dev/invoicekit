package com.shashank.invoicekit.invoice.application.service;

import static com.shashank.invoicekit.invoice.domain.IntakeFixtures.DOMESTIC_EVENT;
import static com.shashank.invoicekit.invoice.domain.IntakeFixtures.DOMESTIC_PAYLOAD;
import static com.shashank.invoicekit.invoice.domain.IntakeFixtures.EXPORT_EVENT;
import static com.shashank.invoicekit.invoice.domain.IntakeFixtures.EXPORT_PAYLOAD;
import static com.shashank.invoicekit.invoice.domain.IntakeFixtures.JSON;
import static com.shashank.invoicekit.invoice.domain.IntakeFixtures.KARNATAKA;
import static com.shashank.invoicekit.invoice.domain.IntakeFixtures.MAHARASHTRA;
import static com.shashank.invoicekit.invoice.domain.IntakeFixtures.SAAS_18;
import static com.shashank.invoicekit.invoice.domain.IntakeFixtures.acmeMaharashtra;
import static com.shashank.invoicekit.invoice.domain.IntakeFixtures.context;
import static com.shashank.invoicekit.invoice.domain.IntakeFixtures.domesticEvent;
import static com.shashank.invoicekit.invoice.domain.IntakeFixtures.draftOf;
import static com.shashank.invoicekit.invoice.domain.IntakeFixtures.event;
import static com.shashank.invoicekit.invoice.domain.IntakeFixtures.seedDeclarations;
import static com.shashank.invoicekit.invoice.domain.IntakeFixtures.subscriptionsConfig;
import static com.shashank.invoicekit.invoice.domain.IntakeFixtures.tree;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.shashank.invoicekit.invoice.domain.entity.InvoiceContext;
import com.shashank.invoicekit.invoice.domain.entity.InvoiceDraft;
import com.shashank.invoicekit.invoice.domain.entity.InvoicePayload;
import com.shashank.invoicekit.invoice.domain.entity.InvoicePayload.Line;
import com.shashank.invoicekit.invoice.domain.entity.InvoicePayload.PlaceOfSupply;
import com.shashank.invoicekit.invoice.domain.entity.InvoiceRequested;
import com.shashank.invoicekit.invoice.domain.entity.SupplyType;
import com.shashank.invoicekit.invoice.domain.exception.RejectedEventException;
import com.shashank.invoicekit.invoice.domain.exception.RejectionCode;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;

/** Checks that the superset examples become exactly the superset's saved payloads. */
class InvoiceAssemblerTest {

  private static final UUID DOMESTIC_ID = UUID.fromString("018f3c1e-7b0a-7d3e-9e0a-2f4c9a6d1b11");
  private static final UUID EXPORT_ID = UUID.fromString("018f3c1e-7b0a-7d3e-9e0a-2f4c9a6d1b12");

  private final InvoiceAssembler assembler = new InvoiceAssembler();

  @Test
  void domesticExampleBecomesTheSupersetPayload() throws JsonProcessingException {
    InvoicePayload payload =
        assembler.assemble(DOMESTIC_ID, draftOf(event(DOMESTIC_EVENT)), context(MAHARASHTRA));

    assertThat(json(payload.withInvoiceNumber("SUBA260000000142")))
        .isEqualTo(tree(DOMESTIC_PAYLOAD));
  }

  @Test
  void exportExampleBecomesTheSupersetPayload() throws JsonProcessingException {
    InvoicePayload payload =
        assembler.assemble(EXPORT_ID, draftOf(event(EXPORT_EVENT)), context(null));

    assertThat(json(payload.withInvoiceNumber("INTA260000000017"))).isEqualTo(tree(EXPORT_PAYLOAD));
  }

  @Test
  void leavesTheInvoiceNumberForNumbering() {
    InvoicePayload payload =
        assembler.assemble(DOMESTIC_ID, draftOf(event(DOMESTIC_EVENT)), context(MAHARASHTRA));

    assertThat(payload.invoiceNumber()).isNull();
  }

  @Test
  void interStateSupplyChargesIgstInsteadOfCgstAndSgst() {
    InvoiceRequested event =
        domesticEvent(
            json -> {
              json.put("placeOfSupplyStateCode", "29");
              json.withObjectProperty("recipient")
                  .put("gstin", "29ABCDE1234F1Z5")
                  .put("stateName", "Karnataka")
                  .put("stateCode", "29");
            });

    InvoicePayload payload = assembler.assemble(DOMESTIC_ID, draftOf(event), context(KARNATAKA));

    Line first = payload.lineItems().getFirst();
    assertThat(payload.supplyType()).isEqualTo(SupplyType.INTER_STATE);
    assertThat(payload.placeOfSupply()).isEqualTo(PlaceOfSupply.ofState(KARNATAKA));
    assertThat(
            List.of(
                first.cgstRate(),
                first.cgstAmount(),
                first.sgstRate(),
                first.sgstAmount(),
                first.igstRate(),
                first.igstAmount(),
                first.lineTotal()))
        .containsExactly("0.00", "0.00", "0.00", "0.00", "18.00", "2430.00", "15930.00");
    assertThat(payload.totals().totalIgst()).isEqualTo("2610.00");
    assertThat(payload.totals().totalCgst()).isEqualTo("0.00");
    assertThat(payload.totals().totalValue()).isEqualTo("17110.00");
  }

  @Test
  void buildsTheInvoiceFromTheDraftNotTheRawEvent() {
    InvoiceRequested event = event(DOMESTIC_EVENT);
    InvoiceDraft draft =
        new InvoiceDraft(event, List.of(event.lineItems().get(1)), Map.of("planName", "Growth"));

    InvoicePayload payload = assembler.assemble(DOMESTIC_ID, draft, context(MAHARASHTRA));

    assertThat(payload.lineItems()).extracting(Line::description).containsExactly("Extra storage");
    assertThat(payload.totals().totalValue()).isEqualTo("1180.00");
    assertThat(payload.attributes()).containsExactly(Map.entry("planName", "Growth"));
  }

  @Test
  void rejectsALineWhoseTaxCodeHasNoRate() {
    InvoiceRequested event =
        domesticEvent(
            json ->
                ((ObjectNode) json.withArrayProperty("lineItems").get(1))
                    .put("taxCode", "SAAS_99"));

    assertRejected(
        () -> assembler.assemble(DOMESTIC_ID, draftOf(event), context(MAHARASHTRA)),
        "taxCode SAAS_99 has no rate on the invoice date");
  }

  @Test
  void rejectsAPlaceOfSupplyThatIsNotAGstState() {
    assertRejected(
        () -> assembler.assemble(DOMESTIC_ID, draftOf(event(DOMESTIC_EVENT)), context(null)),
        "placeOfSupplyStateCode 27 is not a GST state");
  }

  @Test
  void rejectsAnExportFromAnEntityWithoutALut() {
    InvoiceContext withoutLut =
        new InvoiceContext(
            acmeMaharashtra(null, null),
            subscriptionsConfig(),
            null,
            Map.of(SAAS_18.taxCode(), SAAS_18),
            seedDeclarations());

    assertRejected(
        () -> assembler.assemble(EXPORT_ID, draftOf(event(EXPORT_EVENT)), withoutLut),
        "entity ACME_MH has no lutNumber, which an export needs");
  }

  private static void assertRejected(ThrowingCallable assembly, String reason) {
    assertThatThrownBy(assembly)
        .isInstanceOfSatisfying(
            RejectedEventException.class,
            rejected -> {
              assertThat(rejected.code()).isEqualTo(RejectionCode.VALIDATION_FAILED);
              assertThat(rejected.reasons()).containsExactly(reason);
            });
  }

  /** Writes the payload as text and reads it back, so the test sees the real JSON output. */
  private static JsonNode json(InvoicePayload payload) throws JsonProcessingException {
    return JSON.readTree(JSON.writeValueAsString(payload));
  }
}
