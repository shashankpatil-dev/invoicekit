package com.shashank.invoicekit.bootstrap.configuration;

import java.net.URI;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Settings for the object storage that holds finished PDFs. A missing bucket stops the application
 * from starting, instead of failing on the first upload.
 *
 * @param endpoint address of a local fake-gcs-server, for local and test runs only. Left empty, the
 *     application uses real Google Cloud Storage with the default Google credentials
 * @param bucket bucket the PDFs are stored in
 */
@ConfigurationProperties("invoicekit.storage")
public record StorageProperties(URI endpoint, String bucket) {

  public StorageProperties {
    if (bucket == null || bucket.isBlank()) {
      throw new IllegalArgumentException("invoicekit.storage.bucket is required");
    }
  }

  /** Whether storage is a local fake-gcs-server instead of real Google Cloud Storage. */
  public boolean usesLocalEndpoint() {
    return endpoint != null && !endpoint.toString().isBlank();
  }
}
