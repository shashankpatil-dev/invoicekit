package com.shashank.invoicekit.support;

import com.google.cloud.storage.BucketInfo;
import com.google.cloud.storage.Storage;
import com.shashank.invoicekit.bootstrap.configuration.StorageConfiguration;
import com.shashank.invoicekit.bootstrap.configuration.StorageProperties;
import java.net.URI;
import java.nio.ByteBuffer;
import java.util.Base64;
import java.util.zip.CRC32C;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistrar;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;

/**
 * Starts one real fake-gcs-server, the same version as deploy/compose, with the PDF bucket already
 * created. It starts when a test first uses it and is shared by every test after that.
 *
 * <p>Plain tests call {@link #storage()} for a client. Spring tests import it with
 * {@code @Import(FakeGcsFixture.class)}, which points the application's storage settings at it.
 */
@TestConfiguration(proxyBeanMethods = false)
public class FakeGcsFixture {

  public static final String BUCKET = "invoices";

  private static final String IMAGE = "fsouza/fake-gcs-server:1.52.2";
  private static final int PORT = 4443;

  private static final GenericContainer<?> SERVER =
      new GenericContainer<>(IMAGE)
          .withCommand("-scheme", "http", "-port", String.valueOf(PORT))
          .withExposedPorts(PORT)
          .waitingFor(Wait.forHttp("/storage/v1/b").forPort(PORT));

  private static final URI ENDPOINT;

  // Testcontainers stops the server when the test run ends.
  static {
    SERVER.start();
    ENDPOINT = URI.create("http://%s:%d".formatted(SERVER.getHost(), SERVER.getMappedPort(PORT)));
    storage().create(BucketInfo.of(BUCKET));
  }

  /** A client that connects the way local runs do, through the configured endpoint. */
  public static Storage storage() {
    return new StorageConfiguration().storage(new StorageProperties(ENDPOINT, BUCKET));
  }

  /** CRC32C in the form GCS reports it: the big-endian bytes in base64, with the prefix. */
  public static String checksumOf(byte[] bytes) {
    CRC32C crc = new CRC32C();
    crc.update(bytes);
    byte[] bigEndian = ByteBuffer.allocate(Integer.BYTES).putInt((int) crc.getValue()).array();
    return "crc32c:" + Base64.getEncoder().encodeToString(bigEndian);
  }

  /** Points the application's storage settings at the fake server and its bucket. */
  @Bean
  DynamicPropertyRegistrar fakeGcsProperties() {
    return registry -> {
      registry.add("invoicekit.storage.endpoint", ENDPOINT::toString);
      registry.add("invoicekit.storage.bucket", () -> BUCKET);
    };
  }
}
