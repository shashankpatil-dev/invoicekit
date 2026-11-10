package com.shashank.invoicekit.invoice.flow;

import static com.shashank.invoicekit.invoice.flow.IntakeEvents.newEvent;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.google.cloud.storage.Blob;
import com.google.cloud.storage.BlobId;
import com.google.cloud.storage.Storage;
import com.shashank.invoicekit.bootstrap.configuration.RenderConfiguration;
import com.shashank.invoicekit.bootstrap.configuration.StorageConfiguration;
import com.shashank.invoicekit.bootstrap.configuration.StorageProperties;
import com.shashank.invoicekit.documentengine.application.port.outbound.DocumentEngine;
import com.shashank.invoicekit.documentengine.domain.entity.RenderRequest;
import com.shashank.invoicekit.documentengine.domain.entity.RenderedDocument;
import com.shashank.invoicekit.documentengine.domain.exception.RenderRejectedException;
import com.shashank.invoicekit.invoice.application.service.IntakeService;
import com.shashank.invoicekit.invoice.application.service.RenderOutcome;
import com.shashank.invoicekit.invoice.application.service.RenderService;
import com.shashank.invoicekit.invoice.domain.IntakeFixtures;
import com.shashank.invoicekit.invoice.domain.entity.InvoiceRequested;
import com.shashank.invoicekit.invoice.domain.entity.RenderJob;
import com.shashank.invoicekit.invoice.flow.IntakeRows.AuditRow;
import com.shashank.invoicekit.invoice.flow.IntakeRows.OutboxRow;
import com.shashank.invoicekit.invoice.flow.IntakeRows.SavedInvoice;
import com.shashank.invoicekit.support.FakeGcsFixture;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Import;

/**
 * Renders invoices that intake really created, through the real render service, PostgreSQL and
 * fake-gcs-server, and checks what docs/RENDER.md section 2 promises: a numbered invoice becomes
 * one stored PDF and one {@code invoice.generated} message, rendering it again changes nothing, and
 * a rejected render fails the invoice.
 *
 * <p>The document engine is a stub that returns fixed PDF bytes, or rejects every render once a
 * test asks it to. Each job is read from the invoice's {@code invoice.render} outbox row, as the
 * render worker will read it from Kafka.
 */
@IntakeFlowTest
@EnableConfigurationProperties(StorageProperties.class)
@Import({
  FakeGcsFixture.class,
  StorageConfiguration.class,
  RenderConfiguration.class,
  RenderFlowTest.StubDocumentEngine.class
})
class RenderFlowTest {

  private static final byte[] PDF = "%PDF-1.7 render flow test".getBytes(StandardCharsets.UTF_8);
  private static final String REASON = "template subscriptions/invoice.typ is missing";

  @Autowired private IntakeService intake;
  @Autowired private RenderService render;
  @Autowired private IntakeRows rows;
  @Autowired private StubDocumentEngine engine;
  @Autowired private Storage storage;

  @AfterEach
  void reset() {
    rows.deleteAll();
    engine.renderEverything();
  }

  @Test
  void renderedInvoiceIsStoredWithItsPdfAndQueuesOneGeneratedMessage()
      throws JsonProcessingException {
    String sourceEventId = createInvoice();

    RenderOutcome outcome = render.render(renderJobOf(sourceEventId));

    SavedInvoice invoice = rows.invoice(sourceEventId);
    assertThat(outcome).isInstanceOf(RenderOutcome.Stored.class);
    assertThat(invoice.status()).isEqualTo("STORED");
    assertThat(invoice.uri())
        .isEqualTo("gs://invoices/invoices/subscriptions/2026/11/01/" + invoice.id() + ".pdf");
    Blob pdf = storedPdf(invoice);
    assertThat(pdf.getContent()).isEqualTo(PDF);
    assertThat(invoice.checksum())
        .isEqualTo(FakeGcsFixture.checksumOf(PDF))
        .isEqualTo("crc32c:" + pdf.getCrc32c());
    assertThat(generatedMessages(invoice.id())).hasSize(1);
  }

  @Test
  void renderingTheSameJobAgainStoresNothingNewAndQueuesNoSecondMessage()
      throws JsonProcessingException {
    String sourceEventId = createInvoice();
    RenderJob job = renderJobOf(sourceEventId);
    render.render(job);
    SavedInvoice invoice = rows.invoice(sourceEventId);
    long firstGeneration = storedPdf(invoice).getGeneration();

    RenderOutcome again = render.render(job);

    assertThat(again).isInstanceOf(RenderOutcome.Skipped.class);
    assertThat(storedPdf(invoice).getGeneration()).isEqualTo(firstGeneration);
    assertThat(rows.audit(invoice.id()))
        .containsExactly(
            new AuditRow(null, "NUMBERED", "intake"), new AuditRow("NUMBERED", "STORED", "render"));
    assertThat(generatedMessages(invoice.id())).hasSize(1);
  }

  @Test
  void rejectedRenderMarksTheInvoiceFailedAndStoresNothing() throws JsonProcessingException {
    engine.rejectEverything(REASON);
    String sourceEventId = createInvoice();
    RenderJob job = renderJobOf(sourceEventId);

    RenderOutcome outcome = render.render(job);

    SavedInvoice invoice = rows.invoice(sourceEventId);
    assertThat(outcome).isEqualTo(new RenderOutcome.Failed(REASON));
    assertThat(invoice.status()).isEqualTo("FAILED");
    assertThat(invoice.uri()).isNull();
    assertThat(storage.get(BlobId.of(FakeGcsFixture.BUCKET, job.documentPath().value()))).isNull();
    assertThat(rows.audit(invoice.id())).contains(new AuditRow("NUMBERED", "FAILED", "render"));
    assertThat(generatedMessages(invoice.id())).isEmpty();
  }

  /** Sends a new event through intake, and returns its event ID to find its invoice by. */
  private String createInvoice() {
    InvoiceRequested event = newEvent();
    intake.process(event);
    return event.sourceEventId();
  }

  /** The job the render worker will read from the invoice's {@code invoice.render} message. */
  private RenderJob renderJobOf(String sourceEventId) throws JsonProcessingException {
    UUID invoiceId = rows.invoice(sourceEventId).id();
    JsonNode message =
        IntakeFixtures.JSON.readTree(outbox(invoiceId, "invoice.render").getFirst().body());
    JsonNode payload = message.get("payload");
    return new RenderJob(
        UUID.fromString(message.get("invoiceId").asText()),
        message.get("bu").asText(),
        payload.get("financialYear").asText(),
        LocalDate.parse(message.get("invoiceDate").asText()),
        message.get("invoiceNumber").asText(),
        payload.toString());
  }

  private List<OutboxRow> generatedMessages(UUID invoiceId) {
    return outbox(invoiceId, "invoice.generated");
  }

  /** The invoice's outbox rows for one topic. */
  private List<OutboxRow> outbox(UUID invoiceId, String topic) {
    return rows.outbox(invoiceId).stream().filter(row -> row.topic().equals(topic)).toList();
  }

  private Blob storedPdf(SavedInvoice invoice) {
    return storage.get(BlobId.fromGsUtilUri(invoice.uri()));
  }

  /** Answers every render with the same PDF bytes, or rejects every render once told to. */
  static class StubDocumentEngine implements DocumentEngine {

    /** Why every render is refused, or null while renders succeed. */
    private String rejection;

    void rejectEverything(String reason) {
      rejection = reason;
    }

    void renderEverything() {
      rejection = null;
    }

    @Override
    public RenderedDocument render(RenderRequest request) {
      if (rejection != null) {
        throw new RenderRejectedException(rejection);
      }
      return new RenderedDocument(PDF);
    }
  }
}
