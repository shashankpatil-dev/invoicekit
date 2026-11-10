package com.shashank.invoicekit.invoice.adapter.inbound;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shashank.invoicekit.invoice.application.service.IntakeOutcome;
import com.shashank.invoicekit.invoice.application.service.IntakeService;
import com.shashank.invoicekit.invoice.domain.entity.InvoiceRequested;
import com.shashank.invoicekit.invoice.domain.exception.RejectedEventException;
import com.shashank.invoicekit.invoice.domain.exception.RejectionCode;
import java.util.List;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.listener.AcknowledgingMessageListener;
import org.springframework.kafka.support.Acknowledgment;

/**
 * Reads each {@code invoice.requested} message and hands its event to intake. The offset is
 * acknowledged only once intake has saved the invoice or found it was a duplicate.
 *
 * <p>Failures are thrown, not handled here: the container's error handler retries passing failures
 * and sends rejected events to the dead-letter topic. Topic, group and error handling are set in
 * {@code IntakeKafkaConfiguration}.
 */
public class InvoiceRequestedListener implements AcknowledgingMessageListener<String, String> {

  private static final Logger log = LoggerFactory.getLogger(InvoiceRequestedListener.class);

  private final IntakeService intake;
  private final ObjectMapper objectMapper;

  public InvoiceRequestedListener(IntakeService intake, ObjectMapper objectMapper) {
    this.intake = intake;
    this.objectMapper = objectMapper;
  }

  @Override
  public void onMessage(ConsumerRecord<String, String> message, Acknowledgment acknowledgment) {
    InvoiceRequested event = parse(message.value());
    IntakeOutcome outcome = intake.process(event);
    log.info("{} event {} {}", event.businessUnit(), event.sourceEventId(), outcome.summary());
    acknowledgment.acknowledge();
  }

  /**
   * Reads the event from JSON. A message that holds no event can never become an invoice, so it is
   * rejected instead of being retried.
   */
  private InvoiceRequested parse(String json) {
    InvoiceRequested event = read(json);
    if (event == null) {
      throw invalidMessage("the message is JSON null, not an event");
    }
    return event;
  }

  /** Jackson refuses a missing message with an illegal argument, and broken JSON with its own. */
  private InvoiceRequested read(String json) {
    try {
      return objectMapper.readValue(json, InvoiceRequested.class);
    } catch (JsonProcessingException | IllegalArgumentException unreadable) {
      throw invalidMessage(unreadable.getMessage());
    }
  }

  private static RejectedEventException invalidMessage(String reason) {
    return new RejectedEventException(RejectionCode.INVALID_MESSAGE, List.of(reason));
  }
}
