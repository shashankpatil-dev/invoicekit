package com.shashank.invoicekit.bootstrap.configuration;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.cloud.NoCredentials;
import com.google.cloud.storage.Storage;
import com.google.cloud.storage.StorageOptions;
import com.shashank.invoicekit.invoice.application.port.outbound.DocumentStore;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * Checks that the storage client talks to fake-gcs-server only when an endpoint is set, and to real
 * Google Cloud Storage otherwise. No real Google credentials are needed.
 */
class StorageConfigurationTest {

  private static final String REAL_GCS_HOST = "https://storage.googleapis.com/";

  private final ApplicationContextRunner contextRunner =
      new ApplicationContextRunner()
          .withUserConfiguration(StorageConfiguration.class, EnabledProperties.class)
          .withPropertyValues("invoicekit.storage.bucket=invoices");

  @Test
  void usesFakeGcsServerWithoutCredentialsWhenAnEndpointIsSet() {
    contextRunner
        .withPropertyValues("invoicekit.storage.endpoint=http://localhost:4443")
        .run(
            context -> {
              StorageOptions options = context.getBean(Storage.class).getOptions();
              assertThat(options.getHost()).isEqualTo("http://localhost:4443");
              assertThat(options.getCredentials()).isSameAs(NoCredentials.getInstance());
              assertThat(options.getProjectId()).isEqualTo("invoicekit-local");
              assertThat(context).hasSingleBean(DocumentStore.class);
            });
  }

  /** Deployed environments set the endpoint to an empty value, as storage.properties explains. */
  @Test
  void usesRealGcsWithDefaultCredentialsWhenTheEndpointIsEmpty() {
    contextRunner
        .withPropertyValues("invoicekit.storage.endpoint=")
        .run(
            context -> {
              StorageOptions options = context.getBean(Storage.class).getOptions();
              assertThat(options.getHost()).isEqualTo(REAL_GCS_HOST);
              assertThat(options.getCredentials()).isNotInstanceOf(NoCredentials.class);
            });
  }

  @Test
  void refusesToStartWithoutABucket() {
    new ApplicationContextRunner()
        .withUserConfiguration(StorageConfiguration.class, EnabledProperties.class)
        .run(
            context ->
                assertThat(context)
                    .getFailure()
                    .rootCause()
                    .hasMessageContaining("invoicekit.storage.bucket is required"));
  }

  @EnableConfigurationProperties(StorageProperties.class)
  static class EnabledProperties {}
}
