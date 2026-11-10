package com.shashank.invoicekit.invoice.adapter.outbound.gcs;

import com.google.cloud.storage.Blob;
import com.google.cloud.storage.BlobInfo;
import com.google.cloud.storage.Storage;
import com.google.cloud.storage.StorageException;
import com.shashank.invoicekit.invoice.application.port.outbound.DocumentStore;
import com.shashank.invoicekit.invoice.domain.entity.DocumentPath;
import com.shashank.invoicekit.invoice.domain.entity.StoredDocument;

/**
 * Stores invoice PDFs in one Google Cloud Storage bucket, or in fake-gcs-server locally.
 *
 * <p>Each upload is create-only ({@code ifGenerationMatch=0}), so GCS itself refuses to replace an
 * existing PDF, even when two workers upload at the same moment.
 */
public class GcsDocumentStore implements DocumentStore {

  private static final String PDF_CONTENT_TYPE = "application/pdf";
  private static final String CHECKSUM_PREFIX = "crc32c:";
  private static final int PRECONDITION_FAILED = 412;

  private final Storage storage;
  private final String bucket;

  public GcsDocumentStore(Storage storage, String bucket) {
    this.storage = storage;
    this.bucket = bucket;
  }

  /**
   * A {@code 412} answer means the object already exists, so the existing one is read back. Any
   * other storage error is thrown unchanged for the render worker to retry.
   */
  @Override
  public StoredDocument storeIfAbsent(DocumentPath path, byte[] pdf) {
    BlobInfo document =
        BlobInfo.newBuilder(bucket, path.value()).setContentType(PDF_CONTENT_TYPE).build();
    try {
      // Keep this one-request upload: fake-gcs-server ignores the precondition on resumable ones.
      return storedDocumentOf(
          storage.create(document, pdf, Storage.BlobTargetOption.doesNotExist()));
    } catch (StorageException error) {
      if (error.getCode() != PRECONDITION_FAILED) {
        throw error;
      }
      return storedDocumentOf(storage.get(document.getBlobId()));
    }
  }

  private static StoredDocument storedDocumentOf(Blob blob) {
    return new StoredDocument(blob.getBlobId().toGsUtilUri(), CHECKSUM_PREFIX + blob.getCrc32c());
  }
}
