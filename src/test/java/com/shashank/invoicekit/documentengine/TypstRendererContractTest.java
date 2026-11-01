package com.shashank.invoicekit.documentengine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.shashank.invoicekit.bootstrap.configuration.DocumentEngineConfiguration;
import com.shashank.invoicekit.bootstrap.configuration.TypstRendererProperties;
import com.shashank.invoicekit.documentengine.application.port.outbound.DocumentEngine;
import com.shashank.invoicekit.documentengine.domain.entity.RenderRequest;
import com.shashank.invoicekit.documentengine.domain.exception.RenderRejectedException;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.time.Duration;
import java.util.HashSet;
import java.util.Set;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.MountableFile;

/**
 * Checks real PDFs from the real Typst renderer image.
 *
 * <p>The image is built from the repo, so a broken template or Dockerfile fails this test. It needs
 * Docker to run.
 */
@Testcontainers
class TypstRendererContractTest {

  private static final String SUBSCRIPTIONS = "subscriptions";
  private static final float A4_WIDTH_POINTS = 595.28f;
  private static final float A4_HEIGHT_POINTS = 841.89f;
  private static final ObjectMapper JSON = new ObjectMapper();

  @Container
  private static final GenericContainer<?> RENDERER =
      TypstRendererFixture.rendererContainer()
          .withCopyFileToContainer(
              MountableFile.forClasspathResource("templates/missing-font/invoice.typ"),
              "/app/assets/adaption/missing-font/invoice.typ");

  @Test
  void domesticInvoiceShowsEveryRequiredField() throws IOException {
    try (PDDocument pdf = render(domesticPayload())) {
      assertThat(pdf.getNumberOfPages()).isEqualTo(1);
      assertThat(textOf(pdf))
          .contains(
              "SUBMH2627000142",
              "01 Nov 2026",
              "15 Nov 2026",
              "Acme Cloud Private Limited",
              "27AAAAA0000A1Z5",
              "U72900MH2020PTC000000",
              "Example Traders Private Limited",
              "27ABCDE1234F1Z5",
              "Maharashtra (27)",
              "998314",
              "Growth plan",
              "01 Nov 2026 to 30 Nov 2026",
              "₹13,500.00",
              "₹1,305.00",
              "₹17,110.00",
              "Rupees Seventeen Thousand One Hundred Ten Only",
              "1 Corporate Park, Mumbai 400001");
    }
  }

  @Test
  void pageIsA4() throws IOException {
    try (PDDocument pdf = render(domesticPayload())) {
      PDPage page = pdf.getPage(0);
      assertThat(page.getMediaBox().getWidth()).isBetween(A4_WIDTH_POINTS - 1, A4_WIDTH_POINTS + 1);
      assertThat(page.getMediaBox().getHeight())
          .isBetween(A4_HEIGHT_POINTS - 1, A4_HEIGHT_POINTS + 1);
    }
  }

  @Test
  void intraStateInvoiceShowsCgstAndSgstOnly() throws IOException {
    try (PDDocument pdf = render(domesticPayload())) {
      assertThat(textOf(pdf)).contains("CGST", "SGST").doesNotContain("IGST");
    }
  }

  @Test
  void interStateInvoiceShowsIgstOnly() throws IOException {
    try (PDDocument pdf = render(interStatePayload())) {
      assertThat(textOf(pdf))
          .contains("IGST", "Karnataka (29)", "₹2,610.00")
          .doesNotContain("CGST", "SGST");
    }
  }

  @Test
  void declarationsPrintInPayloadOrder() throws IOException {
    ObjectNode payload = domesticPayload();
    ArrayNode declarations = (ArrayNode) payload.get("declarations");
    declarations.insertObject(1).put("code", "REVERSE_CHARGE").put("text", "Reverse charge text.");
    payload.put("reverseCharge", true);

    try (PDDocument pdf = render(payload)) {
      assertThat(textOf(pdf))
          .contains("Tax payable on reverse charge Yes")
          .containsSubsequence(
              "1. We declare that this invoice",
              "2. Reverse charge text.",
              "3. Subscription charges",
              "4. This is an electronically generated invoice");
    }
  }

  @Test
  void smallUsageRateKeepsItsDecimals() throws IOException {
    ObjectNode payload = domesticPayload();
    ((ObjectNode) payload.get("lineItems").get(1)).put("unitPrice", "0.0025");

    try (PDDocument pdf = render(payload)) {
      assertThat(textOf(pdf)).contains("₹0.0025");
    }
  }

  @Test
  void longInvoiceContinuesAcrossPages() throws IOException {
    try (PDDocument pdf = render(payloadWithLines(60))) {
      int pages = pdf.getNumberOfPages();
      assertThat(pages).isGreaterThan(1);

      for (int page = 1; page <= pages; page++) {
        assertThat(textOfPage(pdf, page))
            .contains("Description")
            .contains("Page %d of %d".formatted(page, pages));
      }
      assertThat(textOfPage(pdf, 1)).doesNotContain("Declarations", "DECLARATIONS");
      assertThat(textOfPage(pdf, pages)).contains("DECLARATIONS", "Invoice total");
    }
  }

  @Test
  void payloadValuesAreNeverRunAsTemplateCode() throws IOException {
    String templateCode = "#panic(\"injected\")";
    ObjectNode payload = domesticPayload();
    ((ObjectNode) payload.get("recipient")).put("name", templateCode);

    try (PDDocument pdf = render(payload)) {
      assertThat(textOf(pdf)).contains(templateCode);
    }
  }

  @Test
  void onlyBundledFontsAreEmbedded() throws IOException {
    try (PDDocument pdf = render(domesticPayload())) {
      assertThat(fontsIn(pdf)).isNotEmpty().allMatch(name -> name.contains("SourceSans3"));
    }
  }

  @Test
  void rejectsAnUnknownBusinessUnit() throws IOException {
    assertThatThrownBy(() -> render("unknown", domesticPayload()))
        .isInstanceOf(RenderRejectedException.class)
        .hasMessageContaining("404");
  }

  @Test
  void rejectsInvalidJson() {
    RenderRequest request = new RenderRequest(SUBSCRIPTIONS, "not json");

    assertThatThrownBy(() -> engine().render(request))
        .isInstanceOf(RenderRejectedException.class)
        .hasMessageContaining("400");
  }

  @Test
  void rejectsAPayloadWithMissingFields() {
    RenderRequest request = new RenderRequest(SUBSCRIPTIONS, "{}");

    assertThatThrownBy(() -> engine().render(request))
        .isInstanceOf(RenderRejectedException.class)
        .hasMessageContaining("422");
  }

  @Test
  void rejectsATemplateWithAMissingFont() {
    RenderRequest request = new RenderRequest("missing-font", "{}");

    assertThatThrownBy(() -> engine().render(request))
        .isInstanceOf(RenderRejectedException.class)
        .hasMessageContaining("422")
        .hasMessageContaining("unknown font family");
  }

  private static ObjectNode domesticPayload() throws IOException {
    try (InputStream sample =
        TypstRendererContractTest.class.getResourceAsStream(
            "/payloads/subscriptions-domestic.json")) {
      return (ObjectNode) JSON.readTree(sample);
    }
  }

  /** The domestic sample, changed into a supply from Maharashtra to Karnataka. */
  private static ObjectNode interStatePayload() throws IOException {
    ObjectNode payload = domesticPayload();
    payload.put("supplyType", "INTER_STATE");
    ((ObjectNode) payload.get("placeOfSupply"))
        .put("stateName", "Karnataka")
        .put("stateCode", "29");
    ((ObjectNode) payload.get("totals"))
        .put("totalCgst", "0.00")
        .put("totalSgst", "0.00")
        .put("totalIgst", "2610.00");
    payload
        .get("lineItems")
        .forEach(
            line ->
                ((ObjectNode) line)
                    .put("cgstAmount", "0.00")
                    .put("sgstAmount", "0.00")
                    .put("igstRate", "18.00")
                    .put("igstAmount", "90.00"));
    return payload;
  }

  /** The domestic sample with its first line repeated. Totals are not recalculated. */
  private static ObjectNode payloadWithLines(int count) throws IOException {
    ObjectNode payload = domesticPayload();
    ObjectNode firstLine = (ObjectNode) payload.get("lineItems").get(0);
    ArrayNode lines = payload.putArray("lineItems");
    for (int lineNo = 1; lineNo <= count; lineNo++) {
      lines.add(firstLine.deepCopy().put("lineNo", lineNo));
    }
    return payload;
  }

  private static PDDocument render(ObjectNode payload) throws IOException {
    return render(SUBSCRIPTIONS, payload);
  }

  private static PDDocument render(String businessUnit, ObjectNode payload) throws IOException {
    RenderRequest request = new RenderRequest(businessUnit, payload.toString());
    return Loader.loadPDF(engine().render(request).pdf());
  }

  private static DocumentEngine engine() {
    URI url = URI.create(TypstRendererFixture.urlOf(RENDERER));
    TypstRendererProperties properties = new TypstRendererProperties(url, Duration.ofSeconds(10));
    return new DocumentEngineConfiguration().documentEngine(properties);
  }

  private static String textOf(PDDocument pdf) throws IOException {
    return normalized(new PDFTextStripper().getText(pdf));
  }

  private static String textOfPage(PDDocument pdf, int page) throws IOException {
    PDFTextStripper stripper = new PDFTextStripper();
    stripper.setStartPage(page);
    stripper.setEndPage(page);
    return normalized(stripper.getText(pdf));
  }

  /** PDF text comes back with layout line breaks, so tests compare it on single spaces. */
  private static String normalized(String text) {
    return text.replaceAll("\\s+", " ");
  }

  private static Set<String> fontsIn(PDDocument pdf) throws IOException {
    Set<String> fonts = new HashSet<>();
    for (PDPage page : pdf.getPages()) {
      for (var fontName : page.getResources().getFontNames()) {
        PDFont font = page.getResources().getFont(fontName);
        fonts.add(font.getName());
      }
    }
    return fonts;
  }
}
