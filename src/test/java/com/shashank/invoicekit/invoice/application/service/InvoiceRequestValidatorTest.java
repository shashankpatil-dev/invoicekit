package com.shashank.invoicekit.invoice.application.service;

import static com.shashank.invoicekit.invoice.domain.IntakeFixtures.DOMESTIC_EVENT;
import static com.shashank.invoicekit.invoice.domain.IntakeFixtures.EXPORT_EVENT;
import static com.shashank.invoicekit.invoice.domain.IntakeFixtures.domesticEvent;
import static com.shashank.invoicekit.invoice.domain.IntakeFixtures.event;
import static com.shashank.invoicekit.invoice.domain.IntakeFixtures.exportEvent;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Named.named;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.shashank.invoicekit.invoice.domain.entity.InvoiceRequested;
import com.shashank.invoicekit.invoice.domain.exception.RejectedEventException;
import com.shashank.invoicekit.invoice.domain.exception.RejectionCode;
import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Checks every superset rule that needs no reference data. Each case changes one thing in a valid
 * example event and expects exactly the problems that change causes.
 */
class InvoiceRequestValidatorTest {

  private final InvoiceRequestValidator validator = new InvoiceRequestValidator();

  @Test
  void acceptsTheDomesticExample() {
    assertThatCode(() -> validator.validate(event(DOMESTIC_EVENT))).doesNotThrowAnyException();
  }

  @Test
  void acceptsTheExportExample() {
    assertThatCode(() -> validator.validate(event(EXPORT_EVENT))).doesNotThrowAnyException();
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("brokenDomesticEvents")
  void rejectsABrokenDomesticEvent(Consumer<ObjectNode> change, List<String> reasons) {
    assertRejected(domesticEvent(change), reasons);
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("brokenExportEvents")
  void rejectsABrokenExportEvent(Consumer<ObjectNode> change, List<String> reasons) {
    assertRejected(exportEvent(change), reasons);
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("acceptedChanges")
  void acceptsAValidVariation(Consumer<ObjectNode> change) {
    assertThatCode(() -> validator.validate(domesticEvent(change))).doesNotThrowAnyException();
  }

  @Test
  void reportsEveryProblemTogether() {
    InvoiceRequested event =
        domesticEvent(
            json -> {
              json.put("schemaVersion", 1);
              json.remove("bu");
              json.withObjectProperty("recipient").put("gstin", "bad");
              line(json, 1).put("quantity", "0");
            });

    assertRejected(
        event,
        List.of(
            "schemaVersion must be 2, but was 1",
            "bu is required",
            "recipient.gstin bad is not a valid GSTIN",
            "line 2 quantity must be more than zero"));
  }

  static Stream<Arguments> brokenDomesticEvents() {
    return Stream.of(
        broken(
            "schemaVersion 1",
            json -> json.put("schemaVersion", 1),
            "schemaVersion must be 2, but was 1"),
        broken("bu missing", json -> json.remove("bu"), "bu is required"),
        broken("bu blank", json -> json.put("bu", " "), "bu is required"),
        broken(
            "sourceEventId missing",
            json -> json.remove("sourceEventId"),
            "sourceEventId is required"),
        broken("entityCode missing", json -> json.remove("entityCode"), "entityCode is required"),
        broken(
            "invoiceDate missing", json -> json.remove("invoiceDate"), "invoiceDate is required"),
        broken(
            "supplyCategory missing",
            json -> json.remove("supplyCategory"),
            "supplyCategory is required"),
        broken(
            "reverseCharge missing",
            json -> json.remove("reverseCharge"),
            "reverseCharge is required"),
        broken("currency missing", json -> json.remove("currency"), "currency is required"),
        broken(
            "placeOfSupplyStateCode missing",
            json -> json.remove("placeOfSupplyStateCode"),
            "placeOfSupplyStateCode is required"),
        broken(
            "domestic in USD",
            json -> json.put("currency", "USD").put("exchangeRate", "85.0000"),
            "currency must be INR for DOMESTIC supply"),
        broken(
            "INR at a rate other than 1",
            json -> json.put("exchangeRate", "2.0000"),
            "exchangeRate must be 1 for INR"),
        broken("recipient missing", json -> json.remove("recipient"), "recipient is required"),
        broken(
            "recipient name missing",
            json -> json.withObjectProperty("recipient").remove("name"),
            "recipient.name is required"),
        broken(
            "GSTIN too short",
            json -> json.withObjectProperty("recipient").put("gstin", "27ABCDE1234F1Z"),
            "recipient.gstin 27ABCDE1234F1Z is not a valid GSTIN"),
        broken(
            "GSTIN in lower case",
            json -> json.withObjectProperty("recipient").put("gstin", "27abcde1234f1z5"),
            "recipient.gstin 27abcde1234f1z5 is not a valid GSTIN"),
        broken(
            "GSTIN without the fixed Z",
            json -> json.withObjectProperty("recipient").put("gstin", "27ABCDE1234F1X5"),
            "recipient.gstin 27ABCDE1234F1X5 is not a valid GSTIN"),
        broken(
            "registered recipient without address",
            json -> json.withObjectProperty("recipient").remove("address"),
            "recipient.address is required"),
        broken(
            "registered recipient without state",
            json -> json.withObjectProperty("recipient").remove(List.of("stateName", "stateCode")),
            "recipient.stateName is required",
            "recipient.stateCode is required"),
        broken(
            "unregistered recipient of 50,000 without address or state",
            json -> {
              unregisteredWithoutAddress(json);
              line(json, 0).put("quantity", "34");
            },
            "recipient.address is required",
            "recipient.stateName is required",
            "recipient.stateCode is required"),
        broken(
            "unregistered recipient with an unpriced line",
            json -> {
              unregisteredWithoutAddress(json);
              line(json, 1).remove("unitPrice");
            },
            "line 2 unitPrice is required"),
        broken(
            "unregistered recipient with a line without quantity",
            json -> {
              unregisteredWithoutAddress(json);
              line(json, 1).remove("quantity");
            },
            "line 2 quantity is required"),
        broken(
            "no lines",
            json -> json.putArray("lineItems"),
            "lineItems must have at least one line"),
        broken(
            "line description missing",
            json -> line(json, 1).remove("description"),
            "line 2 description is required"),
        broken(
            "line taxCode missing",
            json -> line(json, 1).remove("taxCode"),
            "line 2 taxCode is required"),
        broken(
            "line unit missing", json -> line(json, 1).remove("unit"), "line 2 unit is required"),
        broken(
            "line quantity missing",
            json -> line(json, 1).remove("quantity"),
            "line 2 quantity is required"),
        broken(
            "line quantity zero",
            json -> line(json, 1).put("quantity", "0"),
            "line 2 quantity must be more than zero"),
        broken(
            "line quantity with 4 decimals",
            json -> line(json, 1).put("quantity", "1.2345"),
            "line 2 quantity must have at most 3 decimal places"),
        broken(
            "line unitPrice missing",
            json -> line(json, 1).remove("unitPrice"),
            "line 2 unitPrice is required"),
        broken(
            "line unitPrice negative",
            json -> line(json, 1).put("unitPrice", "-1"),
            "line 2 unitPrice must not be negative",
            "line 2 discount must not be more than quantity times unitPrice"),
        broken(
            "line unitPrice with 5 decimals",
            json -> line(json, 1).put("unitPrice", "50.00001"),
            "line 2 unitPrice must have at most 4 decimal places"),
        broken(
            "line discount negative",
            json -> line(json, 1).put("discount", "-1.00"),
            "line 2 discount must not be negative"),
        broken(
            "line discount with 3 decimals",
            json -> line(json, 1).put("discount", "0.005"),
            "line 2 discount must have at most 2 decimal places"),
        broken(
            "line discount above the line value",
            json -> line(json, 1).put("discount", "1000.01"),
            "line 2 discount must not be more than quantity times unitPrice"),
        broken(
            "service period ending before it starts",
            json -> line(json, 1).put("servicePeriodStart", "2026-12-01"),
            "line 2 servicePeriodStart must not be after servicePeriodEnd"));
  }

  static Stream<Arguments> brokenExportEvents() {
    return Stream.of(
        broken(
            "currency not ISO 4217",
            json -> json.put("currency", "XYZ"),
            "currency XYZ is not an ISO 4217 code"),
        broken(
            "foreign currency without exchangeRate",
            json -> json.remove("exchangeRate"),
            "exchangeRate is required for a foreign currency or an export"),
        broken(
            "export in INR without exchangeRate",
            json -> {
              json.put("currency", "INR");
              json.remove("exchangeRate");
            },
            "exchangeRate is required for a foreign currency or an export"),
        broken(
            "exchangeRate zero",
            json -> json.put("exchangeRate", "0"),
            "exchangeRate must be more than zero, with at most 4 decimal places"),
        broken(
            "exchangeRate with 5 decimals",
            json -> json.put("exchangeRate", "85.00001"),
            "exchangeRate must be more than zero, with at most 4 decimal places"),
        broken(
            "export with reverse charge",
            json -> json.put("reverseCharge", true),
            "reverseCharge must be false for EXPORT_LUT supply"),
        broken(
            "export without countryCode",
            json -> json.withObjectProperty("recipient").remove("countryCode"),
            "recipient.countryCode is required"),
        broken(
            "export to India",
            json -> json.withObjectProperty("recipient").put("countryCode", "IN"),
            "recipient.countryCode IN must be an ISO country code other than IN"),
        broken(
            "export to an unknown country",
            json -> json.withObjectProperty("recipient").put("countryCode", "XX"),
            "recipient.countryCode XX must be an ISO country code other than IN"),
        broken(
            "export without recipient",
            json -> json.remove("recipient"),
            "recipient.countryCode is required",
            "recipient is required"),
        broken(
            "export without recipient address",
            json -> json.withObjectProperty("recipient").remove("address"),
            "recipient.address is required"));
  }

  static Stream<Arguments> acceptedChanges() {
    return Stream.of(
        accepted("INR at a rate of exactly 1", json -> json.put("exchangeRate", "1.0000")),
        accepted(
            "unregistered recipient below 50,000 without address or state",
            json -> {
              unregisteredWithoutAddress(json);
              line(json, 0).put("quantity", "33");
            }),
        accepted("no discount", json -> line(json, 1).remove("discount")),
        accepted("discount equal to the line value", json -> line(json, 1).put("discount", "1000")),
        accepted("quantity with 3 decimals", json -> line(json, 1).put("quantity", "1.234")),
        accepted("quantity with trailing zeros", json -> line(json, 1).put("quantity", "20.0000")),
        accepted("free line", json -> line(json, 1).put("unitPrice", "0")),
        accepted(
            "one-day service period",
            json -> line(json, 1).put("servicePeriodStart", "2026-11-30")),
        accepted(
            "no service period",
            json -> line(json, 1).remove(List.of("servicePeriodStart", "servicePeriodEnd"))));
  }

  private void assertRejected(InvoiceRequested event, List<String> reasons) {
    assertThatThrownBy(() -> validator.validate(event))
        .isInstanceOfSatisfying(
            RejectedEventException.class,
            rejected -> {
              assertThat(rejected.code()).isEqualTo(RejectionCode.VALIDATION_FAILED);
              assertThat(rejected.reasons()).containsExactlyElementsOf(reasons);
            });
  }

  /**
   * Makes the recipient an unregistered Indian consumer with no address or state. Line 1 is 1,500
   * per seat less 1,500, and line 2 adds 1,000, so 33 seats is 49,000 and 34 seats is 50,500.
   */
  private static void unregisteredWithoutAddress(ObjectNode json) {
    json.withObjectProperty("recipient")
        .remove(List.of("gstin", "address", "stateName", "stateCode"));
  }

  private static ObjectNode line(ObjectNode json, int index) {
    return (ObjectNode) json.withArrayProperty("lineItems").get(index);
  }

  private static Arguments broken(String name, Consumer<ObjectNode> change, String... reasons) {
    return Arguments.of(named(name, change), List.of(reasons));
  }

  private static Arguments accepted(String name, Consumer<ObjectNode> change) {
    return Arguments.of(named(name, change));
  }
}
