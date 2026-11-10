package com.shashank.invoicekit.bootstrap.configuration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shashank.invoicekit.documentengine.application.port.outbound.DocumentEngine;
import com.shashank.invoicekit.invoice.adapter.outbound.postgres.PostgresInvoiceLifecycleStore;
import com.shashank.invoicekit.invoice.application.port.outbound.DocumentStore;
import com.shashank.invoicekit.invoice.application.port.outbound.InvoiceLifecycleStore;
import com.shashank.invoicekit.invoice.application.service.RenderService;
import com.shashank.invoicekit.outbox.application.port.outbound.TraceHeaders;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Connects rendering to PostgreSQL, the document engine and object storage. See docs/RENDER.md
 * section 5.
 */
@Configuration
public class RenderConfiguration {

  /** Moves invoices through rendering, with the current trace on each outbox row it writes. */
  @Bean
  public InvoiceLifecycleStore invoiceLifecycleStore(
      JdbcClient jdbcClient, ObjectMapper objectMapper, TraceHeaders traceHeaders) {
    return new PostgresInvoiceLifecycleStore(jdbcClient, objectMapper, traceHeaders);
  }

  @Bean
  public RenderService renderService(
      InvoiceLifecycleStore lifecycle, DocumentEngine engine, DocumentStore documents) {
    return new RenderService(lifecycle, engine, documents);
  }
}
