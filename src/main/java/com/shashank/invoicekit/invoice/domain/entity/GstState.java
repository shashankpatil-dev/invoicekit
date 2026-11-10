package com.shashank.invoicekit.invoice.domain.entity;

/**
 * An Indian state or union territory as GST knows it.
 *
 * @param code two-digit GST state code, such as {@code 27}
 * @param name state name, such as {@code Maharashtra}
 */
public record GstState(String code, String name) {}
