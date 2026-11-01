package com.shashank.invoicekit.documentengine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import com.shashank.invoicekit.bootstrap.configuration.DocumentEngineConfiguration;
import com.shashank.invoicekit.bootstrap.configuration.TypstRendererProperties;
import com.shashank.invoicekit.documentengine.application.port.outbound.DocumentEngine;
import com.shashank.invoicekit.documentengine.domain.entity.RenderRequest;
import com.shashank.invoicekit.documentengine.domain.exception.RenderUnavailableException;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.MountableFile;

/** Checks that the real renderer stays healthy when it has more work than it can handle. */
@Testcontainers
class TypstRendererOverloadTest {

  private static final int REQUESTS_ABOVE_QUEUE_LIMIT = 12;
  private static final Duration REQUESTS_ARRIVE = Duration.ofMillis(150);

  /** Matches the readiness probe timeout in deploy/kind/document-engine.yaml. */
  private static final Duration HEALTH_LIMIT = Duration.ofSeconds(3);

  @Container
  private static final GenericContainer<?> RENDERER =
      TypstRendererFixture.rendererContainer()
          .withEnv("RENDER_TIMEOUT_SECONDS", "4")
          .withCopyFileToContainer(
              MountableFile.forClasspathResource("templates/slow/invoice.typ"),
              "/app/assets/adaption/slow/invoice.typ")
          .withCopyFileToContainer(
              MountableFile.forClasspathResource("templates/stuck/invoice.typ"),
              "/app/assets/adaption/stuck/invoice.typ");

  private final HttpClient healthClient =
      HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();

  @Test
  void healthAnswersAndExtraRendersAreRefusedDuringOverload() throws Exception {
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      List<Future<Boolean>> renders = new ArrayList<>();
      for (int id = 0; id < REQUESTS_ABOVE_QUEUE_LIMIT; id++) {
        int requestId = id;
        renders.add(executor.submit(() -> rendered(requestId)));
      }
      Thread.sleep(REQUESTS_ARRIVE);

      assertThat(healthStatus()).isEqualTo(200);

      int succeeded = 0;
      for (Future<Boolean> render : renders) {
        succeeded += resultOf(render) ? 1 : 0;
      }
      assertThat(succeeded).isBetween(1, REQUESTS_ABOVE_QUEUE_LIMIT - 1);
    }
  }

  @Test
  void aStuckRenderIsStoppedAndTheRendererRecovers() {
    RenderRequest stuck = new RenderRequest("stuck", "{}");

    assertThatThrownBy(() -> engine().render(stuck)).isInstanceOf(RenderUnavailableException.class);

    await().atMost(Duration.ofSeconds(10)).until(() -> rendered(1000));
  }

  private boolean rendered(int requestId) {
    try {
      engine().render(new RenderRequest("slow", "{\"id\":%d}".formatted(requestId)));
      return true;
    } catch (RenderUnavailableException refused) {
      return false;
    }
  }

  private boolean resultOf(Future<Boolean> render) throws InterruptedException {
    try {
      return render.get();
    } catch (ExecutionException error) {
      throw new AssertionError("Render failed unexpectedly", error);
    }
  }

  private int healthStatus() throws IOException, InterruptedException {
    URI health = URI.create(TypstRendererFixture.urlOf(RENDERER) + "/health");
    HttpRequest request = HttpRequest.newBuilder(health).timeout(HEALTH_LIMIT).build();
    return healthClient.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
  }

  private static DocumentEngine engine() {
    URI url = URI.create(TypstRendererFixture.urlOf(RENDERER));
    TypstRendererProperties properties = new TypstRendererProperties(url, Duration.ofSeconds(10));
    return new DocumentEngineConfiguration().documentEngine(properties);
  }
}
