package com.shashank.invoicekit.invoice.application.port.outbound;

import com.shashank.invoicekit.invoice.domain.entity.DocumentPath;
import com.shashank.invoicekit.invoice.domain.entity.StoredDocument;

/**
 * Saves finished invoice PDFs in object storage. A stored PDF is never replaced, so the checksum
 * saved on the invoice always matches the file.
 */
public interface DocumentStore {

  /**
   * Stores the PDF at the path, but only if nothing is stored there yet. When an object already
   * exists, nothing is written and the existing object's URI and checksum are returned instead. So
   * two workers racing on one invoice end with one file, and both get the same checksum.
   *
   * <p>Storage failures are thrown unchanged, so the caller can retry them.
   *
   * @param path where the PDF belongs
   * @param pdf PDF file contents
   * @return the URI and checksum of the object now stored at the path
   */
  StoredDocument storeIfAbsent(DocumentPath path, byte[] pdf);
}
