package com.shashank.invoicekit.invoice.flow;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.shashank.invoicekit.invoice.domain.IntakeFixtures;
import com.shashank.invoicekit.invoice.domain.entity.InvoiceRequested;
import com.shashank.invoicekit.numbering.domain.entity.FinancialYear;
import com.shashank.invoicekit.numbering.domain.entity.NumberFormat;
import com.shashank.invoicekit.numbering.domain.entity.Shard;
import java.time.LocalDate;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Events for the intake flow tests: the Invoice Superset's domestic example, sent from the
 * temporary {@code TEST_INTAKE} entity, which {@code subscriptions} may use on one shard.
 */
final class IntakeEvents {

  static final String ENTITY_CODE = "TEST_INTAKE";
  static final String BUSINESS_UNIT = "subscriptions";

  /** The financial year of the example's invoice date, 1 November 2026. */
  static final FinancialYear FINANCIAL_YEAR = FinancialYear.of(LocalDate.of(2026, 11, 1));

  /** The {@code subscriptions} number format seeded by V4. */
  static final NumberFormat NUMBER_FORMAT = NumberFormat.parse("SUB{shard}{fy}{seq:10}");

  private static final Shard ONLY_SHARD = new Shard(0);

  private IntakeEvents() {}

  /** The example event on a new event ID, as every new order has. */
  static InvoiceRequested newEvent() {
    return newEvent(event -> {});
  }

  /** The example event on a new event ID, with one change for the rule under test. */
  static InvoiceRequested newEvent(Consumer<ObjectNode> change) {
    return IntakeFixtures.domesticEvent(
        event -> {
          fromTestEntity(event, "intake-" + UUID.randomUUID());
          change.accept(event);
        });
  }

  /** The example event as the JSON text a BU sends, on a given event ID and with one change. */
  static String json(String sourceEventId, Consumer<ObjectNode> change) {
    ObjectNode event = (ObjectNode) IntakeFixtures.tree(IntakeFixtures.DOMESTIC_EVENT);
    fromTestEntity(event, sourceEventId);
    change.accept(event);
    return event.toString();
  }

  /** The example event on a given event ID, so sending it twice is a redelivery. */
  static InvoiceRequested event(String sourceEventId) {
    return newEvent(event -> event.put("sourceEventId", sourceEventId));
  }

  /** The invoice number at a position in the test entity's only series, such as 1. */
  static String number(long sequence) {
    return NUMBER_FORMAT.format(ONLY_SHARD, FINANCIAL_YEAR, sequence);
  }

  private static void fromTestEntity(ObjectNode event, String sourceEventId) {
    event.put("entityCode", ENTITY_CODE);
    event.put("sourceEventId", sourceEventId);
  }
}
