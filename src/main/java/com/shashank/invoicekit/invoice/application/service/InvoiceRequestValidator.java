package com.shashank.invoicekit.invoice.application.service;

import static com.shashank.invoicekit.invoice.domain.entity.DecimalPlaces.EXCHANGE_RATE;
import static com.shashank.invoicekit.invoice.domain.entity.DecimalPlaces.MONEY;
import static com.shashank.invoicekit.invoice.domain.entity.DecimalPlaces.QUANTITY;
import static com.shashank.invoicekit.invoice.domain.entity.DecimalPlaces.UNIT_PRICE;
import static com.shashank.invoicekit.invoice.domain.entity.InvoiceRequested.INR;

import com.shashank.invoicekit.invoice.domain.entity.DecimalPlaces;
import com.shashank.invoicekit.invoice.domain.entity.InvoiceRequested;
import com.shashank.invoicekit.invoice.domain.entity.LineItem;
import com.shashank.invoicekit.invoice.domain.entity.Recipient;
import com.shashank.invoicekit.invoice.domain.entity.SupplyCategory;
import com.shashank.invoicekit.invoice.domain.exception.RejectedEventException;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Currency;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Checks an event against the Invoice Superset rules that need no reference data, collecting every
 * problem in one pass so the dead-letter message lists them all.
 *
 * <p>Rules that need the database, such as known entities, GST states and tax rates, are checked
 * once the reference data is loaded.
 */
public final class InvoiceRequestValidator {

  /** The event contract version this platform reads. */
  public static final int SCHEMA_VERSION = 2;

  /** GSTIN format: state code, PAN, entity number, a fixed {@code Z} and a check character. */
  private static final Pattern GSTIN =
      Pattern.compile("\\d{2}[A-Z]{5}\\d{4}[A-Z][1-9A-Z]Z[0-9A-Z]");

  /**
   * GST Rule 46: an unregistered recipient's address and state are required once the taxable value
   * of a supply reaches this many rupees.
   */
  private static final BigDecimal ADDRESS_THRESHOLD = new BigDecimal("50000");

  private static final String INDIA = "IN";

  /** ISO country codes an export can go to: every country except India. */
  private static final Set<String> FOREIGN_COUNTRIES =
      Arrays.stream(Locale.getISOCountries())
          .filter(code -> !code.equals(INDIA))
          .collect(Collectors.toUnmodifiableSet());

  private static final Set<String> CURRENCIES =
      Currency.getAvailableCurrencies().stream()
          .map(Currency::getCurrencyCode)
          .collect(Collectors.toUnmodifiableSet());

  /** Throws {@link RejectedEventException} with every problem found, or returns quietly. */
  public void validate(InvoiceRequested event) {
    ProblemList problems = new ProblemList();
    checkEnvelope(event, problems);
    checkCurrency(event, problems);
    checkSupplyCategory(event, problems);
    checkRecipient(event, problems);
    checkLines(event, problems);
    problems.rejectIfAny();
  }

  private static void checkEnvelope(InvoiceRequested event, ProblemList problems) {
    problems.check(
        event.schemaVersion() == SCHEMA_VERSION,
        "schemaVersion must be " + SCHEMA_VERSION + ", but was " + event.schemaVersion());
    problems.requireText(event.businessUnit(), "bu");
    problems.requireText(event.sourceEventId(), "sourceEventId");
    problems.requireText(event.entityCode(), "entityCode");
    problems.require(event.invoiceDate(), "invoiceDate");
    problems.require(event.supplyCategory(), "supplyCategory");
    problems.require(event.reverseCharge(), "reverseCharge");
  }

  /** The currency must be a real one, and a foreign currency or an export needs a rate to INR. */
  private static void checkCurrency(InvoiceRequested event, ProblemList problems) {
    String currency = event.currency();
    boolean known =
        problems.requireText(currency, "currency")
            && problems.check(
                CURRENCIES.contains(currency), "currency " + currency + " is not an ISO 4217 code");
    boolean inr = INR.equals(currency);
    boolean needsExchangeRate = known && !inr || event.isExport();
    BigDecimal exchangeRate = event.exchangeRate();
    problems.check(
        exchangeRate != null || !needsExchangeRate,
        "exchangeRate is required for a foreign currency or an export");
    if (exchangeRate != null) {
      problems.check(
          exchangeRate.signum() > 0 && EXCHANGE_RATE.allows(exchangeRate),
          "exchangeRate must be more than zero, with at most 4 decimal places");
      problems.check(
          !inr || exchangeRate.compareTo(BigDecimal.ONE) == 0, "exchangeRate must be 1 for INR");
    }
  }

  private static void checkSupplyCategory(InvoiceRequested event, ProblemList problems) {
    if (event.supplyCategory() == SupplyCategory.DOMESTIC) {
      checkDomestic(event, problems);
    } else if (event.isExport()) {
      checkExport(event, problems);
    }
  }

  private static void checkDomestic(InvoiceRequested event, ProblemList problems) {
    problems.requireText(event.placeOfSupplyStateCode(), "placeOfSupplyStateCode");
    problems.check(
        event.currency() == null || INR.equals(event.currency()),
        "currency must be INR for DOMESTIC supply");
  }

  /** An export goes to a foreign country and is zero-rated, so the recipient never pays tax. */
  private static void checkExport(InvoiceRequested event, ProblemList problems) {
    problems.check(
        !Boolean.TRUE.equals(event.reverseCharge()),
        "reverseCharge must be false for EXPORT_LUT supply");
    String countryCode = event.recipient() == null ? null : event.recipient().countryCode();
    if (problems.requireText(countryCode, "recipient.countryCode")) {
      problems.check(
          FOREIGN_COUNTRIES.contains(countryCode),
          "recipient.countryCode " + countryCode + " must be an ISO country code other than IN");
    }
  }

  /**
   * A registered recipient needs a valid GSTIN, address and state. An export needs an address. An
   * unregistered recipient needs them only for a large domestic supply.
   */
  private static void checkRecipient(InvoiceRequested event, ProblemList problems) {
    Recipient recipient = event.recipient();
    if (!problems.require(recipient, "recipient")) {
      return;
    }
    problems.requireText(recipient.name(), "recipient.name");
    if (recipient.isRegistered()) {
      problems.check(
          GSTIN.matcher(recipient.gstin()).matches(),
          "recipient.gstin " + recipient.gstin() + " is not a valid GSTIN");
    }
    boolean stateRequired = recipient.isRegistered() || isLargeDomesticSupply(event);
    if (stateRequired || event.isExport()) {
      problems.requireText(recipient.address(), "recipient.address");
    }
    if (stateRequired) {
      problems.requireText(recipient.stateName(), "recipient.stateName");
      problems.requireText(recipient.stateCode(), "recipient.stateCode");
    }
  }

  private static boolean isLargeDomesticSupply(InvoiceRequested event) {
    return event.supplyCategory() == SupplyCategory.DOMESTIC
        && taxableValue(event).compareTo(ADDRESS_THRESHOLD) >= 0;
  }

  /** Lines missing a quantity or price are reported on their own, so they count as nothing here. */
  private static BigDecimal taxableValue(InvoiceRequested event) {
    return event.lineItems().stream()
        .filter(line -> line.quantity() != null && line.unitPrice() != null)
        .map(LineItem::taxableValue)
        .reduce(BigDecimal.ZERO, BigDecimal::add);
  }

  private static void checkLines(InvoiceRequested event, ProblemList problems) {
    problems.check(!event.lineItems().isEmpty(), "lineItems must have at least one line");
    for (int index = 0; index < event.lineItems().size(); index++) {
      checkLine(event.lineItems().get(index), "line " + (index + 1), problems);
    }
  }

  private static void checkLine(LineItem line, String label, ProblemList problems) {
    problems.requireText(line.description(), label + " description");
    problems.requireText(line.taxCode(), label + " taxCode");
    problems.requireText(line.unit(), label + " unit");
    if (problems.require(line.quantity(), label + " quantity")) {
      problems.check(line.quantity().signum() > 0, label + " quantity must be more than zero");
      checkPlaces(line.quantity(), QUANTITY, label + " quantity", problems);
    }
    if (problems.require(line.unitPrice(), label + " unitPrice")) {
      problems.check(line.unitPrice().signum() >= 0, label + " unitPrice must not be negative");
      checkPlaces(line.unitPrice(), UNIT_PRICE, label + " unitPrice", problems);
    }
    problems.check(line.discount().signum() >= 0, label + " discount must not be negative");
    checkPlaces(line.discount(), MONEY, label + " discount", problems);
    if (line.quantity() != null && line.unitPrice() != null) {
      problems.check(
          line.discount().compareTo(line.grossValue()) <= 0,
          label + " discount must not be more than quantity times unitPrice");
    }
    if (line.hasServicePeriod()) {
      problems.check(
          !line.servicePeriodStart().isAfter(line.servicePeriodEnd()),
          label + " servicePeriodStart must not be after servicePeriodEnd");
    }
  }

  private static void checkPlaces(
      BigDecimal value, DecimalPlaces places, String field, ProblemList problems) {
    problems.check(
        places.allows(value), field + " must have at most " + places.places() + " decimal places");
  }
}
