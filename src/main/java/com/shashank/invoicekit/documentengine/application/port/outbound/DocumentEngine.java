package com.shashank.invoicekit.documentengine.application.port.outbound;

import com.shashank.invoicekit.documentengine.domain.entity.RenderRequest;
import com.shashank.invoicekit.documentengine.domain.entity.RenderedDocument;

/** Turns a saved invoice payload into a document. */
public interface DocumentEngine {

  /**
   * Renders one invoice.
   *
   * @throws com.shashank.invoicekit.documentengine.domain.exception.RenderRejectedException if the
   *     invoice can't be rendered, and a retry won't help
   * @throws com.shashank.invoicekit.documentengine.domain.exception.RenderUnavailableException if
   *     the engine is unavailable, and the render can be retried
   */
  RenderedDocument render(RenderRequest request);
}
