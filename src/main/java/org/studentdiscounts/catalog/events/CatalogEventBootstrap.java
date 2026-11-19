package org.studentdiscounts.catalog.events;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CatalogEventBootstrap {
  private final NamedParameterJdbcTemplate jdbc;
  private final CatalogEvents events;

  public CatalogEventBootstrap(NamedParameterJdbcTemplate jdbc, CatalogEvents events) {
    this.jdbc = jdbc;
    this.events = events;
  }

  @Transactional
  public int initializeBatch(int limit) {
    if (limit < 1 || limit > 100) throw new IllegalArgumentException("bootstrap.batchSize");
    var ids =
        jdbc.query(
            """
            SELECT id FROM places WHERE event_snapshot IS NULL ORDER BY id LIMIT :limit FOR UPDATE SKIP LOCKED
            """,
            Map.of("limit", limit),
            (rs, n) -> rs.getObject(1, UUID.class));
    ids.forEach(id -> events.record(id, List.of()));
    return ids.size();
  }
}
