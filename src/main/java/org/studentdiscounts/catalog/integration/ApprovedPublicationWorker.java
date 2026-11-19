package org.studentdiscounts.catalog.integration;

import java.sql.Timestamp;
import java.time.Clock;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.studentdiscounts.catalog.model.CardSnapshot;
import org.studentdiscounts.catalog.persistence.CatalogIds;
import org.studentdiscounts.catalog.publication.*;
import tools.jackson.databind.ObjectMapper;

@Service
public class ApprovedPublicationWorker {
  private final NamedParameterJdbcTemplate jdbc;
  private final TransactionTemplate tx;
  private final CatalogPublicationService publications;
  private final ObjectMapper json;
  private final Clock clock;

  public ApprovedPublicationWorker(
      NamedParameterJdbcTemplate jdbc,
      TransactionTemplate tx,
      CatalogPublicationService publications,
      ObjectMapper json,
      Clock clock) {
    this.jdbc = jdbc;
    this.tx = tx;
    this.publications = publications;
    this.json = json;
    this.clock = clock;
  }

  public int publishBatch(int limit) {
    if (limit < 1 || limit > 100) throw new IllegalArgumentException("publication.batchSize");
    int count = 0;
    for (int i = 0; i < limit; i++) {
      if (!Boolean.TRUE.equals(tx.execute(ignored -> publishNext()))) break;
      count++;
    }
    return count;
  }

  private boolean publishNext() {
    var rows =
        jdbc.query(
            """
            SELECT s.submission_id,s.version_id,s.version_no,s.row_version,s.correlation_id,
              v.payload::text,v.base_offer_row_version,m.submission_type,m.resolved_timezone,m.base_place_row_version
            FROM catalog_submission_state s
            JOIN catalog_submission_versions v ON v.version_id=s.version_id AND v.submission_id=s.submission_id AND v.version_no=s.version_no
            JOIN catalog_submission_metadata m ON m.version_id=v.version_id AND m.submission_id=v.submission_id AND m.version_no=v.version_no
            WHERE s.status='APPROVED' AND s.publication_outcome IS NULL
            ORDER BY s.submission_id LIMIT 1 FOR UPDATE OF s SKIP LOCKED
            """,
            Map.of(),
            (rs, n) ->
                new Pending(
                    rs.getObject(1, UUID.class),
                    rs.getObject(2, UUID.class),
                    rs.getInt(3),
                    rs.getInt(4),
                    rs.getString(5),
                    rs.getString(6),
                    rs.getObject(7, Integer.class),
                    rs.getString(8),
                    rs.getString(9),
                    rs.getObject(10, Integer.class)));
    if (rows.isEmpty()) return false;
    Pending pending = rows.getFirst();
    String outcome = "PUBLISHED";
    String error = null;
    try {
      var command =
          new ReviewedSubmission(
              pending.submissionId(),
              pending.versionId(),
              pending.versionNo(),
              pending.rowVersion(),
              ReviewedSubmission.Type.valueOf(pending.type()),
              publicationSnapshot(pending.payload()),
              pending.timezone(),
              pending.offerVersion(),
              pending.placeVersion(),
              pending.correlation());
      publications.publish(command);
    } catch (CatalogConflict conflict) {
      error = conflict.getMessage();
      if (pending.type().equals("CHANGE")
          && java.util.Set.of(
                  "CATALOG_VERSION_CONFLICT",
                  "PLACE_VERSION_CONFLICT",
                  "PLACE_VERSION_REQUIRED",
                  "OFFER_NOT_FOUND",
                  "PLACE_NOT_FOUND")
              .contains(error)) {
        outcome = "CONFLICTED";
        conflictEvent(pending);
      } else outcome = "BLOCKED";
    } catch (IllegalArgumentException | tools.jackson.core.JacksonException invalid) {
      outcome = "BLOCKED";
      error = "INVALID_REVIEWED_METADATA";
    }
    var params =
        new org.springframework.jdbc.core.namedparam.MapSqlParameterSource()
            .addValue("id", pending.submissionId())
            .addValue("outcome", outcome)
            .addValue("error", error);
    jdbc.update(
        "UPDATE catalog_submission_state SET publication_outcome=:outcome,publication_error=:error"
            + " WHERE submission_id=:id",
        params);
    return true;
  }

  private CardSnapshot publicationSnapshot(String payload) {
    var tree = json.readTree(payload);
    for (var offer : tree.path("offers")) {
      var source = offer.path("source");
      if (source.isObject()
          && (source.path("type").isMissingNode()
              || source.path("type").isNull()
              || source.path("type").asText().isBlank()
              || (source.path("url").asText("").isBlank()
                  && source.path("note").asText("").isBlank()))) {
        ((tools.jackson.databind.node.ObjectNode) offer).remove("source");
      }
    }
    return json.treeToValue(tree, CardSnapshot.class);
  }

  private void conflictEvent(Pending pending) {
    UUID id = CatalogIds.newId();
    var now = clock.instant();
    var event =
        json.createObjectNode()
            .put("eventId", id.toString())
            .put("eventType", "OfferPublicationConflicted")
            .put("schemaVersion", 1)
            .put("producer", "catalog")
            .put("occurredAt", now.toString())
            .put("correlationId", pending.correlation());
    event
        .putObject("data")
        .put("submissionId", pending.submissionId().toString())
        .put("versionId", pending.versionId().toString())
        .put("versionNo", pending.versionNo())
        .put("expectedRowVersion", pending.rowVersion())
        .put("reason", "CATALOG_VERSION_CONFLICT");
    jdbc.update(
        "INSERT INTO catalog_outbox(id,topic,message_key,payload,occurred_at)"
            + " VALUES(:id,'submission.workflow.v1',:key,cast(:payload as jsonb),:now)",
        Map.of(
            "id",
            id,
            "key",
            pending.submissionId(),
            "payload",
            json.writeValueAsString(event),
            "now",
            Timestamp.from(now)));
  }

  private record Pending(
      UUID submissionId,
      UUID versionId,
      int versionNo,
      int rowVersion,
      String correlation,
      String payload,
      Integer offerVersion,
      String type,
      String timezone,
      Integer placeVersion) {}
}
