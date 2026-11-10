package com.shashank.invoicekit.bootstrap.configuration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

/** Checks that storage settings need a bucket and treat an empty endpoint as real GCS. */
class StoragePropertiesTest {

  private static final String BUCKET = "invoices";

  @Test
  void usesTheLocalEndpointWhenOneIsSet() {
    StorageProperties properties =
        new StorageProperties(URI.create("http://localhost:4443"), BUCKET);

    assertThat(properties.usesLocalEndpoint()).isTrue();
  }

  @Test
  void usesRealStorageWithoutAnEndpoint() {
    assertThat(new StorageProperties(null, BUCKET).usesLocalEndpoint()).isFalse();
    assertThat(new StorageProperties(URI.create(""), BUCKET).usesLocalEndpoint()).isFalse();
  }

  @ParameterizedTest
  @NullAndEmptySource
  @ValueSource(strings = " ")
  void rejectsAMissingBucket(String bucket) {
    assertThatThrownBy(() -> new StorageProperties(null, bucket))
        .hasMessageContaining("invoicekit.storage.bucket is required");
  }
}
