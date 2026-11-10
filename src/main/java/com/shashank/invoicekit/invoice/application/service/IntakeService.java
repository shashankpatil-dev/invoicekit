package com.shashank.invoicekit.invoice.application.service;

import com.shashank.invoicekit.invoice.application.adaption.AdapterRegistry;
import com.shashank.invoicekit.invoice.application.adaption.AllowedFieldsCheck;
import com.shashank.invoicekit.invoice.application.adaption.InvoiceAdapter;
import com.shashank.invoicekit.invoice.application.port.outbound.ReferenceData;
import com.shashank.invoicekit.invoice.domain.entity.AdapterConfig;
import com.shashank.invoicekit.invoice.domain.entity.BusinessUnitEntity;
import com.shashank.invoicekit.invoice.domain.entity.InvoiceDraft;
import com.shashank.invoicekit.invoice.domain.entity.InvoicePayload;
import com.shashank.invoicekit.invoice.domain.entity.InvoiceRequested;
import com.shashank.invoicekit.invoice.domain.exception.RejectedEventException;
import com.shashank.invoicekit.invoice.domain.exception.RejectionCode;
import com.shashank.invoicekit.numbering.application.service.NumberingRequest;
import com.shashank.invoicekit.numbering.domain.entity.NumberingRule;
import java.util.List;

/**
 * Turns one {@code invoice.requested} event into one numbered invoice, following docs/INTAKE.md
 * section 3.
 *
 * <p>Every check and calculation runs first, with no transaction open and no lock held, so a bad
 * event is rejected without using a number and slow work never blocks a series. Only then does
 * {@link IntakeTransaction} claim the event, number it and save it.
 */
public final class IntakeService {

  private final InvoiceRequestValidator validator;
  private final AdapterRegistry adapters;
  private final ReferenceData referenceData;
  private final AllowedFieldsCheck allowedFieldsCheck;
  private final InvoiceContextFactory contextFactory;
  private final InvoiceAssembler assembler;
  private final PayloadSizeLimit payloadSizeLimit;
  private final IntakeTransaction transaction;

  public IntakeService(
      InvoiceRequestValidator validator,
      AdapterRegistry adapters,
      ReferenceData referenceData,
      AllowedFieldsCheck allowedFieldsCheck,
      InvoiceContextFactory contextFactory,
      InvoiceAssembler assembler,
      PayloadSizeLimit payloadSizeLimit,
      IntakeTransaction transaction) {
    this.validator = validator;
    this.adapters = adapters;
    this.referenceData = referenceData;
    this.allowedFieldsCheck = allowedFieldsCheck;
    this.contextFactory = contextFactory;
    this.assembler = assembler;
    this.payloadSizeLimit = payloadSizeLimit;
    this.transaction = transaction;
  }

  /**
   * Creates the event's invoice, or reports that an earlier delivery already did.
   *
   * @throws RejectedEventException when the event can never become an invoice
   */
  public IntakeOutcome process(InvoiceRequested event) {
    validator.validate(event);
    InvoiceAdapter adapter = adapters.forBusinessUnit(event.businessUnit());
    BusinessUnitEntity access = allowedEntity(event);
    AdapterConfig config = activeConfig(event);
    InvoiceDraft draft = adapter.adapt(event);
    allowedFieldsCheck.check(draft, config);
    InvoicePayload invoice =
        assembler.assemble(TimeOrderedUuid.next(), draft, contextFactory.contextFor(draft, config));
    payloadSizeLimit.check(invoice);
    return transaction.commit(invoice, numberingRequest(event, config, access));
  }

  /** The BU's permission to invoice from the event's entity, with its shard count. */
  private BusinessUnitEntity allowedEntity(InvoiceRequested event) {
    return referenceData
        .businessUnitEntity(event.businessUnit(), event.entityCode())
        .orElseThrow(
            () ->
                new RejectedEventException(
                    RejectionCode.ENTITY_NOT_ALLOWED,
                    List.of(
                        "business unit "
                            + event.businessUnit()
                            + " may not invoice from entity "
                            + event.entityCode())));
  }

  private AdapterConfig activeConfig(InvoiceRequested event) {
    return referenceData
        .activeConfig(event.businessUnit())
        .orElseThrow(
            () ->
                new RejectedEventException(
                    RejectionCode.UNKNOWN_BU,
                    List.of("business unit " + event.businessUnit() + " has no active config")));
  }

  /** The BU's number format, spread over the shards it has on this entity. */
  private static NumberingRequest numberingRequest(
      InvoiceRequested event, AdapterConfig config, BusinessUnitEntity access) {
    return new NumberingRequest(
        event.entityCode(),
        event.businessUnit(),
        event.sourceEventId(),
        event.invoiceDate(),
        new NumberingRule(config.numberFormat(), access.numberShards()));
  }
}
