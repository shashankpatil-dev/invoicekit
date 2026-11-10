package com.shashank.invoicekit.support;

import com.shashank.invoicekit.numbering.domain.entity.FinancialYear;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Reads the numbering series back from the database and checks each one is whole. Run it after
 * every test that numbers invoices, as docs/NUMBERING.md section 14 asks. Shared by the numbering
 * and intake flow tests.
 */
public class SeriesReconciliation {

  /** The reconciliation query from the design: each row is a series with a gap or a repeat. */
  private static final String BROKEN_SERIES =
      """
      SELECT entity_code, bu, financial_year, shard,
             count(*) AS issued, min(sequence_no) AS first, max(sequence_no) AS last
      FROM invoice
      WHERE sequence_no IS NOT NULL
      GROUP BY entity_code, bu, financial_year, shard
      HAVING count(*) <> max(sequence_no)
          OR min(sequence_no) <> 1
          OR count(DISTINCT sequence_no) <> count(*)
      """;

  /**
   * Each row is a series whose counter disagrees with the last number its invoices hold. The full
   * join also catches a counter with no invoices, and invoices with no counter.
   */
  private static final String COUNTERS_OUT_OF_STEP =
      """
      WITH issued AS (
        SELECT entity_code, bu, financial_year, shard, max(sequence_no) AS last_issued
        FROM invoice
        WHERE sequence_no IS NOT NULL
        GROUP BY entity_code, bu, financial_year, shard
      )
      SELECT entity_code, bu, financial_year, shard, issued.last_issued, invoice_counter.last_no
      FROM issued
      FULL JOIN invoice_counter USING (entity_code, bu, financial_year, shard)
      WHERE issued.last_issued IS DISTINCT FROM invoice_counter.last_no
      """;

  private static final String LAST_NUMBERS =
      """
      SELECT shard, last_no FROM invoice_counter
      WHERE entity_code = :entityCode AND bu = :businessUnit AND financial_year = :financialYear
      """;

  private final JdbcClient jdbcClient;

  public SeriesReconciliation(JdbcClient jdbcClient) {
    this.jdbcClient = jdbcClient;
  }

  /** Every series that breaks a rule. Empty means no gaps, no repeats and matching counters. */
  public List<Map<String, Object>> problems() {
    return Stream.of(BROKEN_SERIES, COUNTERS_OUT_OF_STEP)
        .flatMap(check -> jdbcClient.sql(check).query().listOfRows().stream())
        .toList();
  }

  /** Each shard's counter for one entity, business unit and year, as last number by letter. */
  public Map<String, Long> lastNumbersByShard(
      String entityCode, String businessUnit, FinancialYear financialYear) {
    Map<String, Long> lastNumbers = new TreeMap<>();
    jdbcClient
        .sql(LAST_NUMBERS)
        .param("entityCode", entityCode)
        .param("businessUnit", businessUnit)
        .param("financialYear", financialYear.label())
        .query(
            row -> {
              lastNumbers.put(row.getString("shard"), row.getLong("last_no"));
            });
    return lastNumbers;
  }

  /** How many numbers one entity and business unit issued in a year, across all shards. */
  public long issuedCount(String entityCode, String businessUnit, FinancialYear financialYear) {
    return lastNumbersByShard(entityCode, businessUnit, financialYear).values().stream()
        .mapToLong(Long::longValue)
        .sum();
  }
}
