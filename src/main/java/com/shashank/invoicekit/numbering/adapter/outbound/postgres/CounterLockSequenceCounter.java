package com.shashank.invoicekit.numbering.adapter.outbound.postgres;

import com.shashank.invoicekit.numbering.application.port.outbound.SequenceCounter;
import com.shashank.invoicekit.numbering.domain.entity.SeriesKey;
import com.shashank.invoicekit.numbering.domain.exception.SeriesBusyException;
import com.shashank.invoicekit.numbering.domain.exception.SeriesExhaustedException;
import java.sql.SQLException;
import java.time.Duration;
import java.util.Optional;
import java.util.Set;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Counts each series in its own {@code invoice_counter} row, which the increment locks until the
 * caller's transaction ends.
 *
 * <p>A rollback undoes the increment like any other change, so a series never skips a number.
 */
public class CounterLockSequenceCounter implements SequenceCounter {

  /**
   * Stops this transaction from waiting long for the counter lock or running a slow statement, so a
   * stuck counter fails fast instead of holding up the worker. SET takes no bind parameters, so
   * only the whole-millisecond numbers are written in.
   */
  private static final String SET_TIMEOUTS =
      """
      SET LOCAL lock_timeout = '%dms';
      SET LOCAL statement_timeout = '%dms'
      """;

  /**
   * Takes the next number, creating the counter on the series' first use. The caller touches the
   * counter last in its transaction, so the row stays locked only until commit. The WHERE stops a
   * full series from growing past what the format can print, so a number is never cut to fit.
   * PostgreSQL still locks a row the WHERE skips, but returns no row for it.
   */
  private static final String NEXT_NUMBER =
      """
      INSERT INTO invoice_counter AS c (entity_code, bu, financial_year, shard, last_no)
      VALUES (:entityCode, :businessUnit, :financialYear, :shard, 1)
      ON CONFLICT (entity_code, bu, financial_year, shard)
      DO UPDATE SET last_no = c.last_no + 1
      WHERE c.last_no < :maxSequence
      RETURNING last_no
      """;

  /**
   * PostgreSQL's SQL states for a lock wait timeout (55P03) and a statement timeout (57014). They
   * are matched directly because Spring reports 55P03 as an uncategorized error by default.
   */
  private static final Set<String> TIMEOUT_STATES = Set.of("55P03", "57014");

  private final JdbcClient jdbcClient;
  private final String setTimeouts;

  public CounterLockSequenceCounter(
      JdbcClient jdbcClient, Duration lockTimeout, Duration statementTimeout) {
    this.jdbcClient = jdbcClient;
    this.setTimeouts = SET_TIMEOUTS.formatted(lockTimeout.toMillis(), statementTimeout.toMillis());
  }

  @Override
  public long next(SeriesKey series, long maxSequence) {
    jdbcClient.sql(setTimeouts).update();
    return increment(series, maxSequence).orElseThrow(() -> new SeriesExhaustedException(series));
  }

  /**
   * Returns the new number, or nothing when the series is full. Running out of time means another
   * transaction holds the counter, so the caller can roll back and retry.
   */
  private Optional<Long> increment(SeriesKey series, long maxSequence) {
    try {
      return jdbcClient
          .sql(NEXT_NUMBER)
          .param("entityCode", series.entityCode())
          .param("businessUnit", series.businessUnit())
          .param("financialYear", series.financialYear().label())
          .param("shard", series.shard().code())
          .param("maxSequence", maxSequence)
          .query(Long.class)
          .optional();
    } catch (DataAccessException error) {
      if (isTimeout(error)) {
        throw new SeriesBusyException(series, error);
      }
      throw error;
    }
  }

  private static boolean isTimeout(DataAccessException error) {
    return error.getMostSpecificCause() instanceof SQLException sqlError
        && TIMEOUT_STATES.contains(sqlError.getSQLState());
  }
}
