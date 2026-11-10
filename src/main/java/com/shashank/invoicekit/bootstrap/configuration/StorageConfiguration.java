package com.shashank.invoicekit.bootstrap.configuration;

import com.google.cloud.NoCredentials;
import com.google.cloud.storage.Storage;
import com.google.cloud.storage.StorageOptions;
import com.shashank.invoicekit.invoice.adapter.outbound.gcs.GcsDocumentStore;
import com.shashank.invoicekit.invoice.application.port.outbound.DocumentStore;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Connects the document store to Google Cloud Storage, or to fake-gcs-server locally. */
@Configuration
public class StorageConfiguration {

  /** fake-gcs-server accepts any project, so local runs send this fixed one. */
  private static final String LOCAL_PROJECT_ID = "invoicekit-local";

  /**
   * Talks to fake-gcs-server without credentials when an endpoint is set. Otherwise it uses real
   * Google Cloud Storage with the default Google credentials of the pod.
   */
  @Bean
  public Storage storage(StorageProperties properties) {
    StorageOptions options =
        properties.usesLocalEndpoint()
            ? StorageOptions.newBuilder()
                .setHost(properties.endpoint().toString())
                .setProjectId(LOCAL_PROJECT_ID)
                .setCredentials(NoCredentials.getInstance())
                .build()
            : StorageOptions.getDefaultInstance();
    return options.getService();
  }

  @Bean
  public DocumentStore documentStore(Storage storage, StorageProperties properties) {
    return new GcsDocumentStore(storage, properties.bucket());
  }
}
