package com.shashank.invoicekit.documentengine.domain.entity;

/**
 * Asks the document engine to render one invoice.
 *
 * <p>The payload stays a JSON string, so this module doesn't depend on a JSON library or on the
 * invoice model.
 *
 * @param businessUnit business unit whose template is used
 * @param payloadJson saved invoice payload
 */
public record RenderRequest(String businessUnit, String payloadJson) {}
