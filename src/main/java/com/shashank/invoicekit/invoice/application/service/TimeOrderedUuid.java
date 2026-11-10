package com.shashank.invoicekit.invoice.application.service;

import java.security.SecureRandom;
import java.util.UUID;

/**
 * Makes version 7 UUIDs (RFC 9562) for invoice and outbox rows. They start with the creation time
 * in milliseconds, so new keys land at the end of the primary key index instead of on random pages,
 * which keeps inserts fast on very large tables. The remaining 74 bits are random, so IDs made in
 * the same millisecond, on any pod, stay unique.
 */
public final class TimeOrderedUuid {

  /** The version field, 0111, in bits 48 to 51. */
  private static final long VERSION_7 = 0x7000L;

  /** The 12 random bits that follow the version. */
  private static final long RANDOM_A_MASK = 0xFFFL;

  /** The variant field, 10, in the top two bits of the second half. */
  private static final long RFC_VARIANT = 0x8000_0000_0000_0000L;

  /** The 62 random bits after the variant. */
  private static final long RANDOM_B_MASK = 0x3FFF_FFFF_FFFF_FFFFL;

  private static final SecureRandom RANDOM = new SecureRandom();

  private TimeOrderedUuid() {}

  /** A new UUID stamped with the current time. */
  public static UUID next() {
    return at(System.currentTimeMillis());
  }

  /** A new UUID stamped with the given Unix time in milliseconds. */
  static UUID at(long epochMillis) {
    long mostSignificant = (epochMillis << 16) | VERSION_7 | (RANDOM.nextLong() & RANDOM_A_MASK);
    long leastSignificant = RFC_VARIANT | (RANDOM.nextLong() & RANDOM_B_MASK);
    return new UUID(mostSignificant, leastSignificant);
  }
}
