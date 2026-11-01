package com.shashank.invoicekit.documentengine;

import java.nio.file.Path;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.ImageFromDockerfile;

/** Starts the real Typst renderer, built from this repo, for tests that need it. */
final class TypstRendererFixture {

  static final int RENDERER_PORT = 3000;

  private static final String SERVICE_PATH = "services/typst-renderer";
  private static final String TEMPLATES_PATH = "src/main/resources/adaption";

  private TypstRendererFixture() {}

  static GenericContainer<?> rendererContainer() {
    return new GenericContainer<>(rendererImage())
        .withExposedPorts(RENDERER_PORT)
        .waitingFor(Wait.forHttp("/health"));
  }

  static String urlOf(GenericContainer<?> renderer) {
    return "http://%s:%d".formatted(renderer.getHost(), renderer.getMappedPort(RENDERER_PORT));
  }

  private static ImageFromDockerfile rendererImage() {
    return new ImageFromDockerfile("invoicekit-typst-renderer-test", false)
        .withFileFromPath("Dockerfile", Path.of(SERVICE_PATH, "Dockerfile"))
        .withFileFromPath(SERVICE_PATH, Path.of(SERVICE_PATH))
        .withFileFromPath(TEMPLATES_PATH, Path.of(TEMPLATES_PATH));
  }
}
