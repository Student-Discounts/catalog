package org.studentdiscounts.catalog.lifecycle;

import java.sql.Timestamp;
import java.time.Clock;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.studentdiscounts.catalog.events.CatalogEvents;

@Service
public class OfferExpiration {
  private final NamedParameterJdbcTemplate jdbc;
  private final CatalogEvents events;
  private final Clock clock;

  public OfferExpiration(NamedParameterJdbcTemplate jdbc, CatalogEvents events, Clock clock) {
    this.jdbc = jdbc;
    this.events = events;
    this.clock = clock;
  }

  @Transactional
  public int expireBatch(int limit) {
    if (limit < 1 || limit > 100) throw new IllegalArgumentException("expiration.batchSize");
    var now = Timestamp.from(clock.instant());
    var places =
        jdbc.query(
            """
            SELECT p.id FROM places p
            WHERE EXISTS (SELECT 1 FROM offers o WHERE o.place_id=p.id AND o.status='ACTIVE'
              AND o.valid_until < (cast(:now as timestamptz) AT TIME ZONE p.timezone)::date)
            ORDER BY p.id LIMIT :limit FOR UPDATE OF p SKIP LOCKED
            """,
            Map.of("now", now, "limit", limit),
            (rs, n) -> rs.getObject(1, UUID.class));
    int count = 0;
    for (UUID placeId : places) {
      var ids =
          jdbc.query(
              """
              UPDATE offers o SET status='EXPIRED',row_version=o.row_version+1,updated_at=:now
              FROM places p WHERE o.place_id=p.id AND p.id=:id AND o.status='ACTIVE'
                AND o.valid_until < (cast(:now as timestamptz) AT TIME ZONE p.timezone)::date
              RETURNING o.id
              """,
              Map.of("id", placeId, "now", now),
              (rs, n) -> rs.getObject(1, UUID.class));
      events.record(placeId, ids);
      count += ids.size();
    }
    return count;
  }
}
