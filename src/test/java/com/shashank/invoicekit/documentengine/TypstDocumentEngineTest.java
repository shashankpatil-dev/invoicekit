package com.shashank.invoicekit.documentengine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.shashank.invoicekit.bootstrap.configuration.DocumentEngineConfiguration;
import com.shashank.invoicekit.bootstrap.configuration.TypstRendererProperties;
import com.shashank.invoicekit.documentengine.application.port.outbound.DocumentEngine;
import com.shashank.invoicekit.documentengine.domain.entity.RenderRequest;
import com.shashank.invoicekit.documentengine.domain.exception.RenderRejectedException;
import com.shashank.invoicekit.documentengine.domain.exception.RenderUnavailableException;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/** Checks the HTTP contract with the renderer, using a small local server in its place. */
class TypstDocumentEngineTest {

  private static final Duration TIMEOUT = Duration.ofMillis(300);
  private static final RenderRequest REQUEST =
      new RenderRequest("subscriptions", "{\"amount\":\"₹100.00\"}");

  private final AtomicReference<String> receivedPath = new AtomicReference<>();
  private final AtomicReference<String> receivedBody = new AtomicReference<>();
  private final AtomicReference<String> receivedContentType = new AtomicReference<>();

  private HttpServer server;

  @BeforeEach
  void startServer() throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.start();
  }

  @AfterEach
  void stopServer() {
    server.stop(0);
  }

  @Test
  void returnsThePdfFromTheRenderer() {
    respondWith(200, "%PDF-sample");

    byte[] pdf = engine().render(REQUEST).pdf();

    assertThat(new String(pdf, StandardCharsets.UTF_8)).isEqualTo("%PDF-sample");
    assertThat(receivedPath).hasValue("/render/subscriptions");
    assertThat(receivedBody).hasValue(REQUEST.payloadJson());
    assertThat(receivedContentType).hasValue("application/json");
  }

  @ParameterizedTest
  @ValueSource(ints = {400, 404, 422})
  void treatsClientErrorsAsPermanent(int status) {
    respondWith(status, "bad template");

    assertThatThrownBy(() -> engine().render(REQUEST))
        .isInstanceOf(RenderRejectedException.class)
        .hasMessageContaining(String.valueOf(status))
        .hasMessageContaining("bad template");
  }

  @ParameterizedTest
  @ValueSource(ints = {500, 502, 503})
  void treatsServerErrorsAsRetryable(int status) {
    respondWith(status, "failed");

    assertThatThrownBy(() -> engine().render(REQUEST))
        .isInstanceOf(RenderUnavailableException.class);
  }

  @Test
  void treatsAnEmptyDocumentAsRetryable() {
    respondWith(200, "");

    assertThatThrownBy(() -> engine().render(REQUEST))
        .isInstanceOf(RenderUnavailableException.class);
  }

  @Test
  void treatsASlowRendererAsRetryable() {
    server.createContext(
        "/",
        exchange -> {
          try {
            Thread.sleep(TIMEOUT.multipliedBy(3).toMillis());
          } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
          }
          exchange.close();
        });

    assertThatThrownBy(() -> engine().render(REQUEST))
        .isInstanceOf(RenderUnavailableException.class);
  }

  @Test
  void treatsAStoppedRendererAsRetryable() {
    DocumentEngine engine = engine();
    server.stop(0);

    assertThatThrownBy(() -> engine.render(REQUEST)).isInstanceOf(RenderUnavailableException.class);
  }

  @Test
  void readsSettingsAndCreatesTheEngine() {
    new ApplicationContextRunner()
        .withUserConfiguration(DocumentEngineConfiguration.class, EnabledProperties.class)
        .withPropertyValues(
            "invoicekit.render.url=http://localhost:3002", "invoicekit.render.timeout=25s")
        .run(context -> assertThat(context).hasSingleBean(DocumentEngine.class));
  }

  private void respondWith(int status, String body) {
    server.createContext(
        "/",
        exchange -> {
          receivedPath.set(exchange.getRequestURI().getPath());
          receivedBody.set(
              new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
          receivedContentType.set(exchange.getRequestHeaders().getFirst("Content-Type"));
          byte[] response = body.getBytes(StandardCharsets.UTF_8);
          exchange.sendResponseHeaders(status, response.length == 0 ? -1 : response.length);
          if (response.length > 0) {
            exchange.getResponseBody().write(response);
          }
          exchange.close();
        });
  }

  private DocumentEngine engine() {
    URI url = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
    TypstRendererProperties properties = new TypstRendererProperties(url, TIMEOUT);
    return new DocumentEngineConfiguration().documentEngine(properties);
  }

  @EnableConfigurationProperties(TypstRendererProperties.class)
  static class EnabledProperties {}
}
