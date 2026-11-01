package com.shashank.invoicekit.documentengine;

import static org.assertj.core.api.Assertions.assertThat;

import com.shashank.invoicekit.bootstrap.configuration.DocumentEngineConfiguration;
import com.shashank.invoicekit.bootstrap.configuration.TypstRendererProperties;
import com.shashank.invoicekit.documentengine.application.port.outbound.DocumentEngine;
import com.shashank.invoicekit.documentengine.domain.entity.RenderRequest;
import com.shashank.invoicekit.documentengine.domain.entity.RenderedDocument;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Runs the whole module once: Spring settings and wiring, the Java client, the real renderer image,
 * and the finished PDF.
 */
@Testcontainers
class DocumentEngineEndToEndTest {

  @Container
  private static final GenericContainer<?> RENDERER = TypstRendererFixture.rendererContainer();

  @Test
  void rendersASavedPayloadIntoAnInvoicePdf() {
    new ApplicationContextRunner()
        .withUserConfiguration(DocumentEngineConfiguration.class, EnabledProperties.class)
        .withPropertyValues(
            "invoicekit.render.url=" + TypstRendererFixture.urlOf(RENDERER),
            "invoicekit.render.timeout=10s")
        .run(
            context -> {
              DocumentEngine engine = context.getBean(DocumentEngine.class);

              RenderedDocument document =
                  engine.render(new RenderRequest("subscriptions", samplePayload()));

              try (PDDocument pdf = Loader.loadPDF(document.pdf())) {
                assertThat(pdf.getNumberOfPages()).isEqualTo(1);
                assertThat(new PDFTextStripper().getText(pdf))
                    .contains("Tax Invoice", "SUBMH2627000142", "₹17,110.00");
              }
            });
  }

  private static String samplePayload() throws IOException {
    try (InputStream sample =
        DocumentEngineEndToEndTest.class.getResourceAsStream(
            "/payloads/subscriptions-domestic.json")) {
      return new String(sample.readAllBytes(), StandardCharsets.UTF_8);
    }
  }

  @EnableConfigurationProperties(TypstRendererProperties.class)
  static class EnabledProperties {}
}
