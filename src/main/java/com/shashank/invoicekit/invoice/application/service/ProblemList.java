package com.shashank.invoicekit.invoice.application.service;

import com.shashank.invoicekit.invoice.domain.exception.RejectedEventException;
import com.shashank.invoicekit.invoice.domain.exception.RejectionCode;
import java.util.ArrayList;
import java.util.List;

/**
 * Collects the problems found in one event, so all of them are reported together. Each check
 * returns whether it held, so a caller can skip the checks that depend on it.
 */
final class ProblemList {

  private final List<String> problems = new ArrayList<>();

  /** Records the problem unless the condition holds. */
  boolean check(boolean holds, String problem) {
    if (!holds) {
      problems.add(problem);
    }
    return holds;
  }

  /** Records that a field is missing when its value is null. */
  boolean require(Object value, String field) {
    return check(value != null, field + " is required");
  }

  /** Records that a text field is missing when it is null or blank. */
  boolean requireText(String value, String field) {
    return check(value != null && !value.isBlank(), field + " is required");
  }

  /** Rejects the event as invalid when any problem was found. */
  void rejectIfAny() {
    if (!problems.isEmpty()) {
      throw new RejectedEventException(RejectionCode.VALIDATION_FAILED, problems);
    }
  }
}
