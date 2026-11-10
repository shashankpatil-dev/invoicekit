package com.shashank.invoicekit.invoice.adapter.outbound.gcs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.google.cloud.storage.Blob;
import com.google.cloud.storage.BlobId;
import com.google.cloud.storage.BucketInfo;
import com.google.cloud.storage.Storage;
import com.google.cloud.storage.StorageException;
import com.shashank.invoicekit.bootstrap.configuration.StorageConfiguration;
import com.shashank.invoicekit.bootstrap.configuration.StorageProperties;
import com.shashank.invoicekit.invoice.domain.entity.DocumentPath;
import com.shashank.invoicekit.invoice.domain.entity.StoredDocument;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.Base64;
import java.util.UUID;
import java.util.zip.CRC32C;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Checks create-only uploads against a real fake-gcs-server, the same version as deploy/compose.
 * docs/RENDER.md lists its precondition support as a risk, which this test settles. It needs Docker
 * to run.
 */
@Testcontainers
class GcsDocumentStoreTest {

  private static final String IMAGE = "fsouza/fake-gcs-server:1.52.2";
  private static final int PORT = 4443;
  private static final String BUCKET = "invoices";
  private static final LocalDate INVOICE_DATE = LocalDate.of(2026, 11, 1);
  private static final byte[] FIRST_PDF = "%PDF-first".getBytes(StandardCharsets.UTF_8);
  private static final byte[] SECOND_PDF = "%PDF-second".getBytes(StandardCharsets.UTF_8);

  @Container
  private static final GenericContainer<?> FAKE_GCS =
      new GenericContainer<>(IMAGE)
          .withCommand("-scheme", "http", "-port", String.valueOf(PORT))
          .withExposedPorts(PORT)
          .waitingFor(Wait.forHttp("/storage/v1/b").forPort(PORT));

  private static Storage storage;
  private static GcsDocumentStore store;

  /** Connects the way local runs do, through the configured endpoint, and creates the bucket. */
  @BeforeAll
  static void createBucket() {
    URI endpoint =
        URI.create("http://%s:%d".formatted(FAKE_GCS.getHost(), FAKE_GCS.getMappedPort(PORT)));
    storage = new StorageConfiguration().storage(new StorageProperties(endpoint, BUCKET));
    storage.create(BucketInfo.of(BUCKET));
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

  /** CRC32C in the form GCS reports it: the big-endian bytes in base64, with the prefix. */
  private static String checksumOf(byte[] bytes) {
    CRC32C crc = new CRC32C();
    crc.update(bytes);
    byte[] bigEndian = ByteBuffer.allocate(Integer.BYTES).putInt((int) crc.getValue()).array();
    return "crc32c:" + Base64.getEncoder().encodeToString(bigEndian);
  }
}
