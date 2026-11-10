package com.shashank.invoicekit.invoice.flow;

import static com.shashank.invoicekit.invoice.flow.IntakeEvents.BUSINESS_UNIT;
import static com.shashank.invoicekit.invoice.flow.IntakeEvents.ENTITY_CODE;
import static com.shashank.invoicekit.invoice.flow.IntakeEvents.FINANCIAL_YEAR;
import static com.shashank.invoicekit.invoice.flow.IntakeEvents.newEvent;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.shashank.invoicekit.invoice.application.service.IntakeOutcome;
import com.shashank.invoicekit.invoice.application.service.IntakeService;
import com.shashank.invoicekit.invoice.domain.IntakeFixtures;
import com.shashank.invoicekit.invoice.domain.entity.InvoiceRequested;
import com.shashank.invoicekit.invoice.flow.IntakeRows.AuditRow;
import com.shashank.invoicekit.invoice.flow.IntakeRows.ClaimedEvent;
import com.shashank.invoicekit.invoice.flow.IntakeRows.OutboxRow;
import com.shashank.invoicekit.invoice.flow.IntakeRows.SavedInvoice;
import com.shashank.invoicekit.support.SeriesReconciliation;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Sends events through intake into a real PostgreSQL and checks what one new event saves, and that
 * a repeated event saves nothing more, as docs/INTAKE.md sections 2 and 7 promise.
 */
@IntakeFlowTest
class IntakeSavingTest {

  private static final String FIRST_NUMBER = "SUBA260000000001";

  @Autowired private IntakeService intake;
  @Autowired private IntakeRows rows;
  @Autowired private SeriesReconciliation reconciliation;

  @AfterEach
  void deleteRows() {
    rows.deleteAll();
  }

  @Test
  void newEventIsCreatedWithTheFirstNumberOfItsSeries() {
    assertThat(intake.process(newEvent())).isEqualTo(new IntakeOutcome.Created(FIRST_NUMBER));
  }

  @Test
  void newEventSavesItsNumberedInvoiceInItsYearsPartition() {
    InvoiceRequested event = newEvent();

    intake.process(event);

    SavedInvoice invoice = rows.invoice(event.sourceEventId());
    assertThat(invoice.partition()).isEqualTo("invoice_2026_27");
    assertThat(invoice.entityCode()).isEqualTo(ENTITY_CODE);
    assertThat(invoice.invoiceNumber()).isEqualTo(FIRST_NUMBER);
    assertThat(invoice.shard()).isEqualTo("A");
    assertThat(invoice.sequenceNo()).isEqualTo(1);
    assertThat(invoice.status()).isEqualTo("NUMBERED");
    assertThat(invoice.id().version()).isEqualTo(7);
  }

  @Test
  void savedPayloadCarriesTheInvoiceNumberAndTheTestEntity() throws JsonProcessingException {
    InvoiceRequested event = newEvent();

    intake.process(event);

    SavedInvoice invoice = rows.invoice(event.sourceEventId());
    JsonNode payload = json(invoice.payload());
    assertThat(payload.get("invoiceNumber").asText()).isEqualTo(FIRST_NUMBER);
    assertThat(payload.get("invoiceId").asText()).isEqualTo(invoice.id().toString());
    assertThat(payload.at("/supplier/gstin").asText()).isEqualTo("27BBBBB1111B1Z5");
  }

  @Test
  void newEventClaimsItsEventForTheInvoice() {
    InvoiceRequested event = newEvent();

    intake.process(event);

    SavedInvoice invoice = rows.invoice(event.sourceEventId());
    assertThat(rows.event(event.sourceEventId()))
        .isEqualTo(new ClaimedEvent(invoice.id(), FINANCIAL_YEAR.label()));
  }

  @Test
  void newEventWritesOneAuditRow() {
    InvoiceRequested event = newEvent();

    intake.process(event);

    assertThat(rows.audit(rows.invoice(event.sourceEventId()).id()))
        .containsExactly(new AuditRow(null, "NUMBERED", "intake"));
  }

  @Test
  void newEventWritesOneRenderMessageCarryingTheNumberAndThePayload()
      throws JsonProcessingException {
    InvoiceRequested event = newEvent();

    intake.process(event);

    SavedInvoice invoice = rows.invoice(event.sourceEventId());
    List<OutboxRow> outbox = rows.outbox(invoice.id());
    assertThat(outbox).hasSize(1);
    OutboxRow message = outbox.getFirst();
    assertThat(message.id().version()).isEqualTo(7);
    assertThat(message.topic()).isEqualTo("invoice.render");
    assertThat(message.messageKey()).isEqualTo(invoice.id().toString());
    JsonNode body = json(message.body());
    assertThat(body.get("schemaVersion").asInt()).isEqualTo(1);
    assertThat(body.get("invoiceId").asText()).isEqualTo(invoice.id().toString());
    assertThat(body.get("bu").asText()).isEqualTo(BUSINESS_UNIT);
    assertThat(body.get("sourceEventId").asText()).isEqualTo(event.sourceEventId());
    assertThat(body.get("invoiceNumber").asText()).isEqualTo(FIRST_NUMBER);
    assertThat(body.get("invoiceDate").asText()).isEqualTo("2026-11-01");
    assertThat(body.get("payload")).isEqualTo(json(invoice.payload()));
  }

  @Test
  void repeatedEventIsADuplicateThatSavesNothingMoreAndUsesNoNumber() {
    InvoiceRequested event = newEvent();
    intake.process(event);

    IntakeOutcome repeat = intake.process(event);

    assertThat(repeat).isEqualTo(new IntakeOutcome.Duplicate());
    assertThat(List.of("invoice", "invoice_event", "invoice_audit", "outbox"))
        .allSatisfy(table -> assertThat(rows.count(table)).as(table).isEqualTo(1));
    assertThat(reconciliation.lastNumbersByShard(ENTITY_CODE, BUSINESS_UNIT, FINANCIAL_YEAR))
        .isEqualTo(Map.of("A", 1L));
  }

  private static JsonNode json(String text) throws JsonProcessingException {
    return IntakeFixtures.JSON.readTree(text);
  }
}
