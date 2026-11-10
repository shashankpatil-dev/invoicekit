package com.shashank.invoicekit.invoice.domain.entity;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Null-safe immutable copies for the records in this package. An absent collection becomes empty,
 * and maps keep their order, so a saved payload is the same every time it is built.
 */
final class ImmutableCopies {

  private ImmutableCopies() {}

  static <T> List<T> listOf(List<T> list) {
    return list == null ? List.of() : List.copyOf(list);
  }

  static <K, V> Map<K, V> orderedMapOf(Map<K, V> map) {
    return map == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(map));
  }
}
