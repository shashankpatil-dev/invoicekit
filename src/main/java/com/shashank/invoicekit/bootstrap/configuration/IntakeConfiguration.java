package com.shashank.invoicekit.bootstrap.configuration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shashank.invoicekit.invoice.adapter.outbound.postgres.CachedReferenceData;
import com.shashank.invoicekit.invoice.adapter.outbound.postgres.PostgresInvoiceStore;
import com.shashank.invoicekit.invoice.adapter.outbound.postgres.PostgresReferenceData;
import com.shashank.invoicekit.invoice.application.adaption.AdapterRegistry;
import com.shashank.invoicekit.invoice.application.adaption.AdapterStartupCheck;
import com.shashank.invoicekit.invoice.application.adaption.AllowedFieldsCheck;
import com.shashank.invoicekit.invoice.application.adaption.InvoiceAdapter;
import com.shashank.invoicekit.invoice.application.port.outbound.InvoiceStore;
import com.shashank.invoicekit.invoice.application.port.outbound.ReferenceData;
import com.shashank.invoicekit.invoice.application.service.IntakeService;
import com.shashank.invoicekit.invoice.application.service.IntakeTransaction;
import com.shashank.invoicekit.invoice.application.service.InvoiceAssembler;
import com.shashank.invoicekit.invoice.application.service.InvoiceContextFactory;
import com.shashank.invoicekit.invoice.application.service.InvoiceRequestValidator;
import com.shashank.invoicekit.invoice.application.service.PayloadSizeLimit;
import com.shashank.invoicekit.numbering.application.service.NumberingService;
import com.shashank.invoicekit.outbox.application.port.outbound.TraceHeaders;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Connects intake to PostgreSQL, the BU adapters and numbering. Adapters are found as Spring beans,
 * so adding a BU needs no change here.
 */
@Configuration
public class IntakeConfiguration {

  /** Reference data from PostgreSQL, kept in memory for the configured time. */
  @Bean
  public ReferenceData referenceData(
      JdbcClient jdbcClient, ObjectMapper objectMapper, IntakeProperties properties) {
    return new CachedReferenceData(
        new PostgresReferenceData(jdbcClient, objectMapper), properties.referenceCacheTtl());
  }

  /** Saves intake's rows, with the current trace on each outbox row. */
  @Bean
  public InvoiceStore invoiceStore(
      JdbcClient jdbcClient, ObjectMapper objectMapper, TraceHeaders traceHeaders) {
    return new PostgresInvoiceStore(jdbcClient, objectMapper, traceHeaders);
  }

  /**
   * Finds each BU's adapter, after checking that adapters and the database's configs agree. A
   * mismatch stops the application from starting, instead of sending events to the dead-letter
   * topic later.
   */
  @Bean
  public AdapterRegistry adapterRegistry(
      List<InvoiceAdapter> adapters, ReferenceData referenceData) {
    AdapterRegistry registry = new AdapterRegistry(adapters);
    new AdapterStartupCheck()
        .verify(adapters, referenceData.activeConfigs(), referenceData.businessUnitEntities());
    return registry;
  }

  @Bean
  public InvoiceRequestValidator invoiceRequestValidator() {
    return new InvoiceRequestValidator();
  }

  @Bean
  public AllowedFieldsCheck allowedFieldsCheck() {
    return new AllowedFieldsCheck();
  }

  @Bean
  public InvoiceAssembler invoiceAssembler() {
    return new InvoiceAssembler();
  }

  @Bean
  public InvoiceContextFactory invoiceContextFactory(ReferenceData referenceData) {
    return new InvoiceContextFactory(referenceData);
  }

  @Bean
  public PayloadSizeLimit payloadSizeLimit(InvoiceStore store, IntakeProperties properties) {
    return new PayloadSizeLimit(store, properties.maxPayloadBytes());
  }

  @Bean
  public IntakeTransaction intakeTransaction(
      InvoiceStore store, NumberingService numberingService) {
    return new IntakeTransaction(store, numberingService);
  }

  @Bean
  public IntakeService intakeService(
      InvoiceRequestValidator validator,
      AdapterRegistry adapterRegistry,
      ReferenceData referenceData,
      AllowedFieldsCheck allowedFieldsCheck,
      InvoiceContextFactory contextFactory,
      InvoiceAssembler assembler,
      PayloadSizeLimit payloadSizeLimit,
      IntakeTransaction transaction) {
    return new IntakeService(
        validator,
        adapterRegistry,
        referenceData,
        allowedFieldsCheck,
        contextFactory,
        assembler,
        payloadSizeLimit,
        transaction);
  }
}
