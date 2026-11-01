package com.shashank.invoicekit.documentengine.adapter.outbound;

import com.shashank.invoicekit.documentengine.application.port.outbound.DocumentEngine;
import com.shashank.invoicekit.documentengine.domain.entity.RenderRequest;
import com.shashank.invoicekit.documentengine.domain.entity.RenderedDocument;
import com.shashank.invoicekit.documentengine.domain.exception.RenderRejectedException;
import com.shashank.invoicekit.documentengine.domain.exception.RenderUnavailableException;
import java.nio.charset.StandardCharsets;
import org.springframework.http.MediaType;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Renders invoices through the Typst renderer service.
 *
 * <p>This class only translates between the service and the {@link DocumentEngine} contract. The
 * render worker decides what to do about retries.
 */
public class TypstDocumentEngine implements DocumentEngine {

  private final RestClient client;

  public TypstDocumentEngine(RestClient client) {
    this.client = client;
  }

  /** Client errors mean the invoice itself is the problem. Anything else may succeed on retry. */
  @Override
  public RenderedDocument render(RenderRequest request) {
    byte[] pdf;
    try {
      pdf =
          client
              .post()
              .uri("/render/{businessUnit}", request.businessUnit())
              .contentType(MediaType.APPLICATION_JSON)
              .body(request.payloadJson().getBytes(StandardCharsets.UTF_8))
              .retrieve()
              .body(byte[].class);
    } catch (HttpClientErrorException error) {
      throw new RenderRejectedException(
          "Typst renderer rejected invoice for business unit %s with status %s: %s"
              .formatted(
                  request.businessUnit(), error.getStatusCode(), error.getResponseBodyAsString()));
    } catch (RestClientException error) {
      throw new RenderUnavailableException(
          "Typst renderer is unavailable for business unit %s".formatted(request.businessUnit()),
          error);
    }

    if (pdf == null || pdf.length == 0) {
      throw new RenderUnavailableException(
          "Typst renderer returned an empty document for business unit %s"
              .formatted(request.businessUnit()));
    }
    return new RenderedDocument(pdf);
  }
}
