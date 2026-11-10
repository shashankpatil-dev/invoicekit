package com.shashank.invoicekit.invoice.adapter.outbound.gcs;

import static com.shashank.invoicekit.support.FakeGcsFixture.BUCKET;
import static com.shashank.invoicekit.support.FakeGcsFixture.checksumOf;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.google.cloud.storage.Blob;
import com.google.cloud.storage.BlobId;
import com.google.cloud.storage.Storage;
import com.google.cloud.storage.StorageException;
import com.shashank.invoicekit.invoice.domain.entity.DocumentPath;
import com.shashank.invoicekit.invoice.domain.entity.StoredDocument;
import com.shashank.invoicekit.support.FakeGcsFixture;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Checks create-only uploads against a real fake-gcs-server, the same version as deploy/compose.
 * docs/RENDER.md lists its precondition support as a risk, which this test settles. It needs Docker
 * to run.
 */
class GcsDocumentStoreTest {

  private static final LocalDate INVOICE_DATE = LocalDate.of(2026, 11, 1);
  private static final byte[] FIRST_PDF = "%PDF-first".getBytes(StandardCharsets.UTF_8);
  private static final byte[] SECOND_PDF = "%PDF-second".getBytes(StandardCharsets.UTF_8);

  private static Storage storage;
  private static GcsDocumentStore store;

  @BeforeAll
  static void connect() {
    storage = FakeGcsFixture.storage();
    store = new GcsDocumentStore(storage, BUCKET);
  }

  @Test
  void firstUploadCreatesThePdf() {
    DocumentPath path = newPath();

    StoredDocument stored = store.storeIfAbsent(path, FIRST_PDF);

    assertThat(stored.uri()).isEqualTo("gs://invoices/" + path.value());
    assertThat(stored.checksum()).isEqualTo(checksumOf(FIRST_PDF));
    Blob blob = storage.get(BlobId.of(BUCKET, path.value()));
    assertThat(blob.getContentType()).isEqualTo("application/pdf");
    assertThat(blob.getContent()).isEqualTo(FIRST_PDF);
  }

  @Test
  void secondUploadKeepsTheFirstPdfAndReturnsItsChecksum() {
    DocumentPath path = newPath();
    StoredDocument first = store.storeIfAbsent(path, FIRST_PDF);

    StoredDocument second = store.storeIfAbsent(path, SECOND_PDF);

    assertThat(second).isEqualTo(first);
    assertThat(second.checksum()).isEqualTo(checksumOf(FIRST_PDF));
    assertThat(storage.get(BlobId.of(BUCKET, path.value())).getContent()).isEqualTo(FIRST_PDF);
  }

  @Test
  void uploadsToDifferentPathsAreIndependent() {
    DocumentPath firstPath = newPath();
    DocumentPath secondPath = newPath();

    StoredDocument first = store.storeIfAbsent(firstPath, FIRST_PDF);
    StoredDocument second = store.storeIfAbsent(secondPath, SECOND_PDF);

    assertThat(first.checksum()).isEqualTo(checksumOf(FIRST_PDF));
    assertThat(second.checksum()).isEqualTo(checksumOf(SECOND_PDF));
    assertThat(second.uri()).isEqualTo("gs://invoices/" + secondPath.value());
  }

  @Test
  void throwsOtherStorageErrorsUnchanged() {
    GcsDocumentStore missingBucket = new GcsDocumentStore(storage, "missing-bucket");

    assertThatThrownBy(() -> missingBucket.storeIfAbsent(newPath(), FIRST_PDF))
        .isInstanceOfSatisfying(
            StorageException.class, error -> assertThat(error.getCode()).isEqualTo(404));
  }

  private static DocumentPath newPath() {
    return new DocumentPath("subscriptions", INVOICE_DATE, UUID.randomUUID());
  }
}
