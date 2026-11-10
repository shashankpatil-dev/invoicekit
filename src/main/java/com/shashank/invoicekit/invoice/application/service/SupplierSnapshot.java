package com.shashank.invoicekit.invoice.application.service;

import com.shashank.invoicekit.invoice.domain.entity.InvoicePayload.Supplier;
import com.shashank.invoicekit.invoice.domain.entity.LegalEntity;

/**
 * Copies the entity's details into the payload, so editing the entity later never changes an
 * invoice already issued.
 */
final class SupplierSnapshot {

  Supplier of(LegalEntity entity) {
    return new Supplier(
        entity.entityCode(),
        entity.legalName(),
        entity.tradeName(),
        entity.gstin(),
        entity.stateName(),
        entity.stateCode(),
        entity.address(),
        entity.registeredOfficeAddress(),
        entity.cin(),
        entity.pan(),
        entity.lutNumber(),
        entity.authorisedSignatory(),
        entity.bankDetails(),
        entity.contactEmail(),
        entity.contactPhone(),
        entity.terms());
  }
}
