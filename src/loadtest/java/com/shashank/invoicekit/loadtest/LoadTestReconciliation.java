package com.shashank.invoicekit.loadtest;

import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Reads the numbering series back after a load test phase and reports whether each one is whole,
 * using the same checks as the numbering flow tests.
 */
@Component
@Profile("loadtest")
class LoadTestReconciliation {

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

  /** Each of the entity's series: what its invoices hold next to what its counter says. */
  private static final String SERIES =
      """
      WITH issued AS (
        SELECT entity_code, bu, financial_year, shard,
               count(*) AS invoices, min(sequence_no) AS first, max(sequence_no) AS last
        FROM invoice
        WHERE sequence_no IS NOT NULL
        GROUP BY entity_code, bu, financial_year, shard
      )
      SELECT bu, financial_year, shard, issued.invoices, issued.first, issued.last,
             invoice_counter.last_no
      FROM issued
      FULL JOIN invoice_counter USING (entity_code, bu, financial_year, shard)
      WHERE entity_code = :entityCode
      ORDER BY bu, financial_year, shard
      """;

  private final JdbcClient jdbcClient;
  private final String entityCode;

  LoadTestReconciliation(JdbcClient jdbcClient, LoadTestProperties properties) {
    this.jdbcClient = jdbcClient;
    this.entityCode = properties.entityCode();
  }

  /** Checks every series and summarises the entity's invoices and counters. */
  Report report() {
    return new Report(problems(), invoiceCount(), series());
  }

  private List<Map<String, Object>> problems() {
    return Stream.of(BROKEN_SERIES, COUNTERS_OUT_OF_STEP)
        .flatMap(check -> jdbcClient.sql(check).query().listOfRows().stream())
        .toList();
  }

  private long invoiceCount() {
    return jdbcClient
        .sql("SELECT count(*) FROM invoice WHERE entity_code = ?")
        .param(entityCode)
        .query(Long.class)
        .single();
  }

  private List<Map<String, Object>> series() {
    return jdbcClient.sql(SERIES).param("entityCode", entityCode).query().listOfRows();
  }

  /**
   * What the database holds after a phase.
   *
   * @param problems series with a gap, a repeat or a counter out of step; empty when all are whole
   * @param invoices how many dummy invoices exist
   * @param series each series' invoice count, first and last sequence, and counter's last number
   */
  record Report(
      List<Map<String, Object>> problems, long invoices, List<Map<String, Object>> series) {}
}
