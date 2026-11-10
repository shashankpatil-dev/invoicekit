package com.shashank.invoicekit.invoice.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.shashank.invoicekit.documentengine.application.port.outbound.DocumentEngine;
import com.shashank.invoicekit.documentengine.domain.entity.RenderRequest;
import com.shashank.invoicekit.documentengine.domain.entity.RenderedDocument;
import com.shashank.invoicekit.documentengine.domain.exception.RenderRejectedException;
import com.shashank.invoicekit.documentengine.domain.exception.RenderUnavailableException;
import com.shashank.invoicekit.invoice.application.port.outbound.DocumentStore;
import com.shashank.invoicekit.invoice.application.port.outbound.InvoiceLifecycleStore;
import com.shashank.invoicekit.invoice.domain.entity.DocumentPath;
import com.shashank.invoicekit.invoice.domain.entity.InvoiceStatus;
import com.shashank.invoicekit.invoice.domain.entity.RenderJob;
import com.shashank.invoicekit.invoice.domain.entity.StoredDocument;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * Checks each step of a render against small in-memory ports: what is skipped, stored, failed and
 * thrown, as docs/RENDER.md sections 3 and 6 describe.
 */
class RenderServiceTest {

  private static final UUID INVOICE_ID = UUID.fromString("0190f3a2-7c1e-7b4d-9a6f-2e8c1d5b3a70");
  private static final LocalDate INVOICE_DATE = LocalDate.of(2026, 11, 1);
  private static final String PAYLOAD = "{\"invoiceNumber\":\"SUBA260000000001\"}";
  private static final byte[] PDF = "%PDF-test".getBytes(StandardCharsets.UTF_8);
  private static final RenderJob JOB =
      new RenderJob(
          INVOICE_ID, "subscriptions", "2026-27", INVOICE_DATE, "SUBA260000000001", PAYLOAD);
  private static final DocumentPath PATH =
      new DocumentPath("subscriptions", INVOICE_DATE, INVOICE_ID);
  private static final StoredDocument STORED =
      new StoredDocument("gs://invoices/" + PATH.value(), "crc32c:AAAAAA==");

  private final FakeLifecycleStore lifecycle = new FakeLifecycleStore();
  private final RecordingEngine engine = new RecordingEngine();
  private final RecordingDocumentStore documents = new RecordingDocumentStore();
  private final RenderService service = new RenderService(lifecycle, engine, documents);

  @Test
  void skipsAnAbsentInvoiceWithoutRendering() {
    RenderOutcome outcome = service.render(JOB);

    assertThat(outcome).isEqualTo(new RenderOutcome.Skipped("it does not exist"));
    assertThat(engine.requests).isEmpty();
  }

  @ParameterizedTest
  @EnumSource(value = InvoiceStatus.class, names = "NUMBERED", mode = EnumSource.Mode.EXCLUDE)
  void skipsAnInvoicePastNumberedWithoutRendering(InvoiceStatus status) {
    lifecycle.status = status;

    RenderOutcome outcome = service.render(JOB);

    assertThat(outcome).isEqualTo(new RenderOutcome.Skipped("it is already " + status));
    assertThat(engine.requests).isEmpty();
    assertThat(lifecycle.status).isEqualTo(status);
  }

  @Test
  void storesTheRenderedPdfAtItsPathAndFinishesTheInvoice() {
    lifecycle.status = InvoiceStatus.NUMBERED;

    RenderOutcome outcome = service.render(JOB);

    assertThat(outcome).isEqualTo(new RenderOutcome.Stored(STORED));
    assertThat(engine.requests).containsExactly(new RenderRequest("subscriptions", PAYLOAD));
    assertThat(documents.stored).containsOnlyKeys(PATH);
    assertThat(documents.stored.get(PATH)).isEqualTo(PDF);
    assertThat(lifecycle.status).isEqualTo(InvoiceStatus.STORED);
    assertThat(lifecycle.finishedWith).isEqualTo(STORED);
  }

  @Test
  void marksARejectedInvoiceFailedWithTheEnginesReasonAndStoresNothing() {
    lifecycle.status = InvoiceStatus.NUMBERED;
    engine.answer =
        () -> {
          throw new RenderRejectedException("template is missing");
        };

    RenderOutcome outcome = service.render(JOB);

    assertThat(outcome).isEqualTo(new RenderOutcome.Failed("template is missing"));
    assertThat(lifecycle.status).isEqualTo(InvoiceStatus.FAILED);
    assertThat(lifecycle.failedReason).isEqualTo("template is missing");
    assertThat(documents.stored).isEmpty();
  }

  @Test
  void skipsARejectedInvoiceThatAnotherWorkerStoredWhileThisOneRendered() {
    lifecycle.status = InvoiceStatus.NUMBERED;
    engine.answer =
        () -> {
          lifecycle.status = InvoiceStatus.STORED;
          throw new RenderRejectedException("template is missing");
        };

    RenderOutcome outcome = service.render(JOB);

    assertThat(outcome).isInstanceOf(RenderOutcome.Skipped.class);
    assertThat(lifecycle.status).isEqualTo(InvoiceStatus.STORED);
    assertThat(lifecycle.failedReason).isNull();
  }

  @Test
  void rethrowsAnUnavailableEngineUnchangedWithoutFinishingOrFailing() {
    lifecycle.status = InvoiceStatus.NUMBERED;
    RenderUnavailableException unavailable = new RenderUnavailableException("renderer is busy");
    engine.answer =
        () -> {
          throw unavailable;
        };

    assertThatThrownBy(() -> service.render(JOB)).isSameAs(unavailable);
    assertThat(documents.stored).isEmpty();
    assertThatNothingWasWritten();
  }

  @Test
  void rethrowsAStorageErrorUnchangedWithoutFinishingOrFailing() {
    lifecycle.status = InvoiceStatus.NUMBERED;
    IllegalStateException storageDown = new IllegalStateException("storage is down");
    documents.failure = storageDown;

    assertThatThrownBy(() -> service.render(JOB)).isSameAs(storageDown);
    assertThatNothingWasWritten();
  }

  @Test
  void reportsAlreadyDoneWhenTheFinishChangesNothing() {
    lifecycle.status = InvoiceStatus.NUMBERED;
    engine.answer =
        () -> {
          lifecycle.status = InvoiceStatus.STORED;
          return new RenderedDocument(PDF);
        };

    RenderOutcome outcome = service.render(JOB);

    assertThat(outcome)
        .isEqualTo(new RenderOutcome.Skipped("another worker moved it on from NUMBERED first"));
    assertThat(lifecycle.finishedWith).isNull();
  }

  private void assertThatNothingWasWritten() {
    assertThat(lifecycle.status).isEqualTo(InvoiceStatus.NUMBERED);
    assertThat(lifecycle.finishedWith).isNull();
    assertThat(lifecycle.failedReason).isNull();
  }

  /** One invoice, whose status the writes change only from NUMBERED, as PostgreSQL's do. */
  private static final class FakeLifecycleStore implements InvoiceLifecycleStore {

    /** The invoice's status, or null when it doesn't exist. */
    private InvoiceStatus status;

    private StoredDocument finishedWith;
    private String failedReason;

    @Override
    public Optional<InvoiceStatus> status(UUID invoiceId, String financialYear) {
      return Optional.ofNullable(status);
    }

    @Override
    public boolean finish(RenderJob job, StoredDocument document) {
      if (status != InvoiceStatus.NUMBERED) {
        return false;
      }
      status = InvoiceStatus.STORED;
      finishedWith = document;
      return true;
    }

    @Override
    public boolean markFailed(RenderJob job, String reason) {
      if (status != InvoiceStatus.NUMBERED) {
        return false;
      }
      status = InvoiceStatus.FAILED;
      failedReason = reason;
      return true;
    }
  }

  /** Records each request and answers with the PDF, unless a test sets another answer. */
  private static final class RecordingEngine implements DocumentEngine {

    private final List<RenderRequest> requests = new ArrayList<>();
    private Supplier<RenderedDocument> answer = () -> new RenderedDocument(PDF);

    @Override
    public RenderedDocument render(RenderRequest request) {
      requests.add(request);
      return answer.get();
    }
  }

  /** Keeps the first PDF stored at each path, or throws the failure a test sets. */
  private static final class RecordingDocumentStore implements DocumentStore {

    private final Map<DocumentPath, byte[]> stored = new HashMap<>();
    private RuntimeException failure;

    @Override
    public StoredDocument storeIfAbsent(DocumentPath path, byte[] pdf) {
      if (failure != null) {
        throw failure;
      }
      stored.putIfAbsent(path, pdf);
      return STORED;
    }
  }
}
