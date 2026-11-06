package org.studentdiscounts.catalog.publication;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.studentdiscounts.catalog.model.*;
import org.studentdiscounts.catalog.persistence.CatalogIds;
import org.studentdiscounts.catalog.persistence.CatalogRepository;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

@Service
public class CatalogPublicationService {
  private final CatalogRepository catalog;
  private final NamedParameterJdbcTemplate jdbc;
  private final ObjectMapper json;
  private final Clock clock;

  public CatalogPublicationService(
      CatalogRepository catalog, NamedParameterJdbcTemplate jdbc, ObjectMapper json, Clock clock) {
    this.catalog = catalog;
    this.jdbc = jdbc;
    this.json = json;
    this.clock = clock;
  }

  @Transactional
  public PublicationResult publish(ReviewedSubmission reviewed) {
    String hash = fingerprint(reviewed);
    jdbc.update(
        """
        INSERT INTO catalog_publications(version_id,submission_id,version_no,command_hash)
        VALUES(:version,:submission,:no,:hash) ON CONFLICT DO NOTHING
        """,
        Map.of(
            "version",
            reviewed.versionId(),
            "submission",
            reviewed.submissionId(),
            "no",
            reviewed.versionNo(),
            "hash",
            hash));
    var receipts =
        jdbc.query(
            "SELECT * FROM catalog_publications WHERE version_id=:id FOR UPDATE",
            Map.of("id", reviewed.versionId()),
            (rs, n) -> {
              if (!hash.equals(rs.getString("command_hash")))
                throw new CatalogConflict("VERSION_CONTENT_CONFLICT");
              if (rs.getTimestamp("published_at") == null) return new Receipt(null);
              List<UUID> ids =
                  Arrays.stream((Object[]) rs.getArray("offer_ids").getArray())
                      .map(id -> UUID.fromString(id.toString()))
                      .toList();
              return new Receipt(
                  new PublicationResult(
                      rs.getObject("place_id", UUID.class),
                      ids,
                      rs.getTimestamp("published_at").toInstant()));
            });
    if (receipts.isEmpty()) throw new CatalogConflict("VERSION_IDENTITY_CONFLICT");
    if (receipts.getFirst().result() != null) return receipts.getFirst().result();
    Instant now = clock.instant();
    ZoneId zone = ZoneId.of(reviewed.resolvedTimezone());
    for (OfferData offer : reviewed.payload().offers()) {
      if (offer.validUntil() != null
          && offer.validUntil().isBefore(now.atZone(zone).toLocalDate())) {
        throw new CatalogConflict("OFFER_ALREADY_EXPIRED");
      }
    }
    if (!catalog.lockActiveCategory(reviewed.payload().place().categoryCode())) {
      throw new CatalogConflict("CATEGORY_NOT_ACTIVE");
    }
    Place place = resolvePlace(reviewed, zone, now);
    List<UUID> ids = new ArrayList<>();
    for (int i = 0; i < reviewed.payload().offers().size(); i++) {
      OfferData data = reviewed.payload().offers().get(i);
      UUID id;
      if (reviewed.type() == ReviewedSubmission.Type.CHANGE) {
        Offer current =
            catalog.lockOffer(data.id()).orElseThrow(() -> new CatalogConflict("OFFER_NOT_FOUND"));
        if (!current.placeId().equals(place.id())
            || current.rowVersion() != reviewed.baseOfferRowVersion()) {
          throw new CatalogConflict("CATALOG_VERSION_CONFLICT");
        }
        catalog.updateOffer(
            current.id(), data, reviewed.versionId(), reviewed.baseOfferRowVersion(), now);
        id = current.id();
      } else {
        id = catalog.insertOffer(place.id(), data, reviewed.versionId(), now);
      }
      final int index = i;
      List<Attachment> attachments =
          reviewed.payload().attachments().stream()
              .filter(a -> a.offerIndex() == null || a.offerIndex() == index)
              .map(a -> new Attachment(a.mediaAssetId(), a.purpose(), 0))
              .toList();
      catalog.saveSnapshot(
          id, new CardSnapshot(place.data(), List.of(data.identified(id)), attachments));
      ids.add(id);
    }
    jdbc.update(
        """
        UPDATE catalog_publications SET place_id=:place,offer_ids=cast(:offers as uuid[]),published_at=:now
        WHERE version_id=:version
        """,
        Map.of(
            "place",
            place.id(),
            "offers",
            "{" + String.join(",", ids.stream().map(UUID::toString).toList()) + "}",
            "now",
            Timestamp.from(now),
            "version",
            reviewed.versionId()));
    saveOutbox(reviewed, ids, now);
    return new PublicationResult(place.id(), ids, now);
  }

  private Place resolvePlace(ReviewedSubmission reviewed, ZoneId zone, Instant now) {
    PlaceData data = reviewed.payload().place();
    if (reviewed.type() == ReviewedSubmission.Type.NEW_PLACE)
      return catalog.insertPlace(data, zone, now);
    Place current =
        catalog.lockPlace(data.id()).orElseThrow(() -> new CatalogConflict("PLACE_NOT_FOUND"));
    if (current.data().equals(data) && current.timezone().equals(zone)) return current;
    if (reviewed.type() != ReviewedSubmission.Type.CHANGE
        || reviewed.basePlaceRowVersion() == null) {
      throw new CatalogConflict("PLACE_VERSION_REQUIRED");
    }
    if (current.rowVersion() != reviewed.basePlaceRowVersion())
      throw new CatalogConflict("PLACE_VERSION_CONFLICT");
    return catalog.updatePlace(data, zone, reviewed.basePlaceRowVersion(), now);
  }

  private void saveOutbox(ReviewedSubmission reviewed, List<UUID> ids, Instant now) {
    UUID id = CatalogIds.newId();
    ObjectNode event =
        json.createObjectNode()
            .put("eventId", id.toString())
            .put("eventType", "OfferPublished")
            .put("schemaVersion", 1)
            .put("producer", "catalog")
            .put("occurredAt", now.toString())
            .put("correlationId", reviewed.correlationId());
    ObjectNode data =
        event
            .putObject("data")
            .put("submissionId", reviewed.submissionId().toString())
            .put("versionId", reviewed.versionId().toString())
            .put("versionNo", reviewed.versionNo())
            .put("expectedRowVersion", reviewed.expectedSubmissionRowVersion());
    var offers = data.putArray("offerIds");
    ids.forEach(value -> offers.add(value.toString()));
    jdbc.update(
        """
        INSERT INTO catalog_outbox(id,version_id,topic,message_key,payload,occurred_at)
        VALUES(:id,:version,'submission.workflow.v1',:key,cast(:payload as jsonb),:now)
        """,
        new MapSqlParameterSource()
            .addValue("id", id)
            .addValue("version", reviewed.versionId())
            .addValue("key", reviewed.submissionId())
            .addValue("payload", json.writeValueAsString(event))
            .addValue("now", Timestamp.from(now)));
  }

  private String fingerprint(ReviewedSubmission reviewed) {
    ObjectNode tree = json.valueToTree(reviewed);
    tree.remove("correlationId");
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256")
                  .digest(json.writeValueAsString(tree).getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException(impossible);
    }
  }

  private record Receipt(PublicationResult result) {}
}
