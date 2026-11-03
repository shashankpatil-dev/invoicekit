package com.shashank.invoicekit.numbering.adapter.outbound.postgres;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.shashank.invoicekit.numbering.domain.entity.FinancialYear;
import com.shashank.invoicekit.numbering.domain.entity.SeriesKey;
import com.shashank.invoicekit.numbering.domain.entity.Shard;
import com.shashank.invoicekit.numbering.domain.exception.SeriesExhaustedException;
import com.shashank.invoicekit.support.PostgresFixture;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase.Replace;
import org.springframework.boot.test.autoconfigure.jdbc.JdbcTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Checks how the counter counts in a real PostgreSQL.
 *
 * <p>Each test runs in a transaction that is rolled back afterwards, so tests never see each
 * other's counters. {@link CounterLockSequenceCounterTransactionTest} covers commits, rollbacks and
 * locks.
 */
@JdbcTest(properties = "spring.flyway.locations=classpath:db/migration,classpath:db/testseries")
@AutoConfigureTestDatabase(replace = Replace.NONE)
@Import(PostgresFixture.class)
class CounterLockSequenceCounterTest {

  private static final String ENTITY_CODE = "TEST_NUMBERING";
  private static final String BUSINESS_UNIT = "numbering-test-sharded";
  private static final FinancialYear FINANCIAL_YEAR = new FinancialYear(2026);
  private static final SeriesKey SERIES =
      new SeriesKey(ENTITY_CODE, BUSINESS_UNIT, FINANCIAL_YEAR, new Shard(0));

  /** The largest sequence a {@code {seq:10}} format can print. */
  private static final long MAX_SEQUENCE = 9_999_999_999L;

  @Autowired private JdbcClient jdbcClient;

  private CounterLockSequenceCounter counter;

  @BeforeEach
  void createCounter() {
    counter =
        new CounterLockSequenceCounter(jdbcClient, Duration.ofSeconds(2), Duration.ofSeconds(5));
  }

  @Test
  void newSeriesStartsAtOneAndCountsUp() {
    assertThat(next(SERIES)).isEqualTo(1);
    assertThat(next(SERIES)).isEqualTo(2);
    assertThat(next(SERIES)).isEqualTo(3);
  }

  @Test
  void eachShardBusinessUnitAndFinancialYearCountsOnItsOwn() {
    next(SERIES);
    next(SERIES);

    assertThat(next(new SeriesKey(ENTITY_CODE, BUSINESS_UNIT, FINANCIAL_YEAR, new Shard(1))))
        .isEqualTo(1);
    assertThat(next(new SeriesKey(ENTITY_CODE, "numbering-test", FINANCIAL_YEAR, new Shard(0))))
        .isEqualTo(1);
    assertThat(
            next(new SeriesKey(ENTITY_CODE, BUSINESS_UNIT, new FinancialYear(2027), new Shard(0))))
        .isEqualTo(1);
    assertThat(next(SERIES)).isEqualTo(3);
  }

  @Test
  void fullSeriesIsRefusedAndKeepsItsLastNumber() {
    storeLastNumber(MAX_SEQUENCE);

    assertThatThrownBy(() -> next(SERIES))
        .isInstanceOf(SeriesExhaustedException.class)
        .extracting("series")
        .isEqualTo(SERIES);
    assertThat(lastNumber()).isEqualTo(MAX_SEQUENCE);
  }

  @Test
  void lastNumberLeftIsStillIssued() {
    storeLastNumber(MAX_SEQUENCE - 1);

    assertThat(next(SERIES)).isEqualTo(MAX_SEQUENCE);
  }

  @Test
  void otherDatabaseErrorsPassThroughUnchanged() {
    SeriesKey unknownEntity =
        new SeriesKey("NO_SUCH_ENTITY", BUSINESS_UNIT, FINANCIAL_YEAR, new Shard(0));

    assertThatThrownBy(() -> next(unknownEntity))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  private long next(SeriesKey series) {
    return counter.next(series, MAX_SEQUENCE);
  }

  private void storeLastNumber(long lastNumber) {
    jdbcClient
        .sql(
            """
            INSERT INTO invoice_counter (entity_code, bu, financial_year, shard, last_no)
            VALUES (?, ?, ?, ?, ?)
            """)
        .params(
            ENTITY_CODE, BUSINESS_UNIT, FINANCIAL_YEAR.label(), SERIES.shard().code(), lastNumber)
        .update();
  }

  private long lastNumber() {
    return jdbcClient
        .sql(
            """
            SELECT last_no FROM invoice_counter
            WHERE entity_code = ? AND bu = ? AND financial_year = ? AND shard = ?
            """)
        .params(ENTITY_CODE, BUSINESS_UNIT, FINANCIAL_YEAR.label(), SERIES.shard().code())
        .query(Long.class)
        .single();
  }
}
