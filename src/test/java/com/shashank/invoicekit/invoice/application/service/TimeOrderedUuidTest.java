package com.shashank.invoicekit.invoice.application.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Checks the IDs are RFC 9562 version 7 UUIDs that sort in the order they were made. */
class TimeOrderedUuidTest {

  private static final long NOV_10_2026_MILLIS = 1_794_268_800_000L;

  @Test
  void isAVersionSevenUuidWithTheRfcVariant() {
    UUID id = TimeOrderedUuid.next();

    assertThat(id.version()).isEqualTo(7);
    assertThat(id.variant()).isEqualTo(2);
  }

  @Test
  void startsWithItsCreationTimeInMilliseconds() {
    UUID id = TimeOrderedUuid.at(NOV_10_2026_MILLIS);

    assertThat(id.getMostSignificantBits() >>> 16).isEqualTo(NOV_10_2026_MILLIS);
  }

  /** PostgreSQL orders UUIDs byte by byte, which is the order of their text form. */
  @Test
  void aLaterMillisecondSortsAfterAnEarlierOne() {
    UUID earlier = TimeOrderedUuid.at(NOV_10_2026_MILLIS);
    UUID later = TimeOrderedUuid.at(NOV_10_2026_MILLIS + 1);

    assertThat(later.toString()).isGreaterThan(earlier.toString());
  }

  @Test
  void increasesOverTime() throws InterruptedException {
    UUID earlier = TimeOrderedUuid.next();
    Thread.sleep(2);
    UUID later = TimeOrderedUuid.next();

    assertThat(later.toString()).isGreaterThan(earlier.toString());
  }

  @Test
  void idsMadeInTheSameMillisecondDiffer() {
    assertThat(TimeOrderedUuid.at(NOV_10_2026_MILLIS))
        .isNotEqualTo(TimeOrderedUuid.at(NOV_10_2026_MILLIS));
  }
}
