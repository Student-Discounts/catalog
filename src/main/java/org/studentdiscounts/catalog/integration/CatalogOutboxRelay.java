package org.studentdiscounts.catalog.integration;

import java.sql.Timestamp;
import java.time.Clock;
import java.util.*;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class CatalogOutboxRelay {
  private final NamedParameterJdbcTemplate jdbc;
  private final TransactionTemplate transactions;
  private final Map<String, EventTransport> transports;
  private final Clock clock;
  private volatile long scanCursor;
  private static final org.slf4j.Logger LOG =
      org.slf4j.LoggerFactory.getLogger(CatalogOutboxRelay.class);

  public CatalogOutboxRelay(
      NamedParameterJdbcTemplate jdbc,
      TransactionTemplate transactions,
      List<EventTransport> transports,
      Clock clock) {
    this.jdbc = jdbc;
    this.transactions = transactions;
    this.clock = clock;
    Map<String, EventTransport> mapped = new HashMap<>();
    for (var transport : transports)
      for (var topic : transport.topics()) {
        if (mapped.putIfAbsent(topic, transport) != null)
          throw new IllegalArgumentException("duplicate transport: " + topic);
      }
    this.transports = Map.copyOf(mapped);
  }

  public int relayBatch(int limit) {
    if (limit < 1 || limit > 100) throw new IllegalArgumentException("outbox.batchSize");
    if (transports.isEmpty()) return 0;
    int count = 0;
    Set<UUID> failed = new HashSet<>();
    for (int i = 0; i < limit; i++) {
      boolean sent;
      try {
        sent = Boolean.TRUE.equals(transactions.execute(ignored -> relayNext(failed)));
      } catch (DeliveryFailure failure) {
        transactions.executeWithoutResult(ignored ->
            jdbc.update(
                "UPDATE catalog_outbox SET"
                    + " next_attempt_at=:retry,delivery_failures=delivery_failures+1 WHERE id=:id"
                    + " AND published_at IS NULL",
                Map.of(
                    "retry",
                    Timestamp.from(clock.instant().plusSeconds(5)),
                    "id",
                    failure.eventId)));
        failed.add(failure.eventId);
        LOG.warn(
            "Catalog event delivery deferred; eventId={}; failureType={}",
            failure.eventId,
            failure.getCause().getClass().getSimpleName());
        continue;
      }
      if (!sent) break;
      count++;
    }
    return count;
  }

  private boolean relayNext(Set<UUID> failed) {
    var params =
        new org.springframework.jdbc.core.namedparam.MapSqlParameterSource()
            .addValue("topics", transports.keySet())
            .addValue("now", Timestamp.from(clock.instant()))
            .addValue("cursor", scanCursor);
    String excluded = failed.isEmpty() ? "" : " AND e.id NOT IN (:failed) ";
    if (!failed.isEmpty()) params.addValue("failed", failed);
    var rows =
        jdbc.query(
            """
            SELECT e.id,e.topic,e.message_key,e.payload::text,e.delivery_sequence FROM catalog_outbox e
            WHERE e.published_at IS NULL AND e.topic IN (:topics) AND e.next_attempt_at<=:now
            AND NOT EXISTS (SELECT 1 FROM catalog_outbox older
                WHERE older.topic=e.topic AND older.message_key=e.message_key
                AND older.published_at IS NULL AND older.delivery_sequence<e.delivery_sequence)
            """
                + excluded
                + " ORDER BY CASE WHEN e.delivery_sequence>:cursor THEN 0 ELSE 1"
                + " END,e.delivery_sequence LIMIT 1 FOR UPDATE OF e SKIP LOCKED",
            params,
            (rs, n) ->
                new Event(
                    rs.getObject(1, UUID.class),
                    rs.getString(2),
                    rs.getObject(3, UUID.class).toString(),
                    rs.getString(4),
                    rs.getLong(5)));
    if (rows.isEmpty()) return false;
    var event = rows.getFirst();
    scanCursor = event.sequence();
    try {
      transports.get(event.topic()).send(event.topic(), event.key(), event.payload());
    } catch (RuntimeException failure) {
      throw new DeliveryFailure(event.id(), failure);
    }
    jdbc.update(
        "UPDATE catalog_outbox SET published_at=:now WHERE id=:id",
        Map.of("id", event.id(), "now", Timestamp.from(clock.instant())));
    return true;
  }

  private static final class DeliveryFailure extends RuntimeException {
    private final UUID eventId;

    private DeliveryFailure(UUID eventId, RuntimeException cause) {
      super("catalog delivery remains pending", cause);
      this.eventId = eventId;
    }
  }

  private record Event(UUID id, String topic, String key, String payload, long sequence) {}
}
