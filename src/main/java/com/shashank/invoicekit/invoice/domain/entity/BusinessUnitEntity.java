package com.shashank.invoicekit.invoice.domain.entity;

/**
 * Allows one business unit to invoice from one entity, as a row in {@code bu_entity}.
 *
 * @param businessUnit business unit, such as {@code subscriptions}
 * @param entityCode entity (GSTIN holder) it may invoice from
 * @param numberShards how many parallel number series the pair uses, 1 to 26
 */
public record BusinessUnitEntity(String businessUnit, String entityCode, int numberShards) {}
