package com.shashank.invoicekit.documentengine.domain.entity;

/**
 * A finished invoice document.
 *
 * @param pdf PDF file contents
 */
public record RenderedDocument(byte[] pdf) {}
