package com.shashank.invoicekit.outbox.flow;

import static com.shashank.invoicekit.outbox.flow.OutboxTopics.FIRST;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shashank.invoicekit.outbox.adapter.outbound.postgres.PostgresOutboxRepository;
import com.shashank.invoicekit.outbox.application.port.outbound.OutboxRepository;
import com.shashank.invoicekit.outbox.application.service.OutboxCleanup;
import com.shashank.invoicekit.support.PostgresFixture;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase.Replace;
import org.springframework.boot.test.autoconfigure.jdbc.JdbcTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Runs outbox cleanup against a real PostgreSQL and checks that it deletes only sent rows older
 * than the retention, one batch at a time. The setup matches the other PostgreSQL slice tests, so
 * they share one database. Each test's rows are rolled back afterwards.
 */
@JdbcTest(properties = "spring.flyway.locations=classpath:db/migration,classpath:db/testseries")
@AutoConfigureTestDatabase(replace = Replace.NONE)
@Import(PostgresFixture.class)
class OutboxCleanupTest {

  private static final Duration RETENTION = Duration.ofHours(24);
  private static final int BATCH_SIZE = 2;

  @Autowired private JdbcClient jdbcClient;

  private OutboxRows rows;
  private OutboxRepository repository;

  @BeforeEach
  void createRowsAndRepository() {
    rows = new OutboxRows(jdbcClient);
    repository = spy(new PostgresOutboxRepository(jdbcClient, new ObjectMapper()));
  }

  @Test
  void deletesSentRowsOlderThanTheRetentionAndKeepsRecentOnes() {
    UUID expired = rows.sent(FIRST, newKey(), RETENTION.plusHours(1));
    UUID recent = rows.sent(FIRST, newKey(), RETENTION.minusHours(1));

    cleanup().deleteExpired();

    assertThat(rows.exists(expired)).isFalse();
    assertThat(rows.exists(recent)).isTrue();
  }

  @Test
  void neverDeletesUnsentRowsHoweverOld() {
    UUID unsent = rows.unsentFor(FIRST, RETENTION.multipliedBy(7));

    cleanup().deleteExpired();

    assertThat(rows.exists(unsent)).isTrue();
  }

  @Test
  void deletesInBatchesUntilNoExpiredRowIsLeft() {
    List<UUID> expired =
        IntStream.range(0, 2 * BATCH_SIZE + 1)
            .mapToObj(row -> rows.sent(FIRST, newKey(), RETENTION.plusHours(1)))
            .toList();

    assertThat(cleanup().deleteExpired()).isEqualTo(expired.size());

    verify(repository, times(3)).deleteSentBefore(any(), eq(BATCH_SIZE));
    assertThat(expired).noneMatch(rows::exists);
  }

  private OutboxCleanup cleanup() {
    return new OutboxCleanup(repository, RETENTION, BATCH_SIZE);
  }

  private static String newKey() {
    return UUID.randomUUID().toString();
  }
}
