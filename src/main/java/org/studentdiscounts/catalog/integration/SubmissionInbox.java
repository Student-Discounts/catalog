package org.studentdiscounts.catalog.integration;

import java.time.Instant;
import java.time.ZoneId;
import java.util.*;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.studentdiscounts.catalog.publication.ReviewedSubmission;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Service
public class SubmissionInbox {
  public static final String EVENTS = "submission.events.v1";
  public static final String METADATA = "catalog.publication.metadata.v1";
  private final NamedParameterJdbcTemplate jdbc;
  private final ObjectMapper json;

  public SubmissionInbox(NamedParameterJdbcTemplate jdbc, ObjectMapper json) {
    this.jdbc = jdbc;
    this.json = json;
  }

  @Transactional
  public void accept(String topic, String key, String body) {
    if (!Set.of(EVENTS, METADATA).contains(topic)
        || body == null
        || body.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 262144)
      throw new IllegalArgumentException("submission.message");
    JsonNode event = json.readTree(body);
    UUID eventId = uuid(event, "eventId");
    if (!text(event, "producer").equals("submission") || number(event, "schemaVersion", 1) != 1)
      throw new IllegalArgumentException("submission.envelope");
    Instant.parse(text(event, "occurredAt"));
    String correlation = text(event, "correlationId");
    if (!correlation.matches("[A-Za-z0-9._:-]{1,64}"))
      throw new IllegalArgumentException("correlationId");
    JsonNode data = event.path("data");
    UUID submission = uuid(data, "submissionId");
    if (!submission.toString().equals(key)) throw new IllegalArgumentException("submission.key");
    String type = text(event, "eventType");
    if (topic.equals(METADATA) && !type.equals("SubmissionPublicationMetadata"))
      throw new IllegalArgumentException("metadata.eventType");
    if (topic.equals(EVENTS)
        && !Set.of("SubmissionSubmitted", "SubmissionVersionCreated", "SubmissionStatusChanged")
            .contains(type)) throw new IllegalArgumentException("submission.eventType");
    if (topic.equals(EVENTS)) sequence(event);
    if (topic.equals(METADATA)) {
      requireFields(
          event,
          Set.of(
              "eventId",
              "eventType",
              "producer",
              "schemaVersion",
              "occurredAt",
              "correlationId",
              "data"));
      requireFields(
          data,
          Set.of(
              "submissionId",
              "versionId",
              "versionNo",
              "type",
              "resolvedTimezone",
              "basePlaceRowVersion"));
    }
    var params =
        new MapSqlParameterSource()
            .addValue("event", eventId)
            .addValue("topic", topic)
            .addValue("payload", json.writeValueAsString(event))
            .addValue("id", submission);
    int added =
        jdbc.update(
            "INSERT INTO catalog_submission_inbox(event_id,topic,payload)"
                + " VALUES(:event,:topic,cast(:payload as jsonb)) ON CONFLICT(event_id) DO NOTHING",
            params);
    if (added == 0) {
      boolean same =
          Boolean.TRUE.equals(
              jdbc.queryForObject(
                  "SELECT topic=:topic AND payload=cast(:payload as jsonb) FROM"
                      + " catalog_submission_inbox WHERE event_id=:event",
                  params,
                  Boolean.class));
      if (!same) throw new IllegalArgumentException("EVENT_ID_REUSED");
      return;
    }
    jdbc.update(
        "INSERT INTO catalog_submission_state(submission_id) VALUES(:id) ON CONFLICT DO NOTHING",
        params);
    long current =
        jdbc.queryForObject(
            "SELECT aggregate_sequence FROM catalog_submission_state WHERE submission_id=:id FOR"
                + " UPDATE",
            params,
            Long.class);
    if (topic.equals(METADATA)) {
      saveMetadata(submission, data);
      return;
    }
    long sequence = sequence(event);
    int rowVersion = number(data, "rowVersion", 0);
    UUID version = null;
    Integer versionNo = null;
    String status;
    if (type.equals("SubmissionStatusChanged")) {
      status = text(data, "toStatus");
      if (!Set.of(
              "DRAFT",
              "SUBMITTED",
              "VALIDATING",
              "PENDING_REVIEW",
              "IN_REVIEW",
              "CHANGES_REQUESTED",
              "APPROVED",
              "REJECTED",
              "CANCELLED",
              "PUBLISHED",
              "SUSPENDED",
              "ARCHIVED")
          .contains(status)) throw new IllegalArgumentException("submission.status");
      if (!data.path("versionId").isMissingNode() && !data.path("versionId").isNull()) {
        version = uuid(data, "versionId");
        versionNo = number(data, "versionNo", 1);
      }
      if (status.equals("APPROVED") && version == null)
        throw new IllegalArgumentException("approval.version");
    } else {
      version = uuid(data, "versionId");
      versionNo = number(data, "versionNo", 1);
      if (number(data, "payloadSchemaVersion", 1) != 1 || !data.path("payload").isObject())
        throw new IllegalArgumentException("version.payload");
      if (!text(data, "contentSha256").matches("[0-9a-f]{64}"))
        throw new IllegalArgumentException("version.hash");
      if (!SubmissionPayloadHash.hash(data.path("payload")).equals(text(data, "contentSha256")))
        throw new IllegalArgumentException("VERSION_HASH_MISMATCH");
      uuid(data, "authorId");
      Integer base = optionalNumber(data, "baseOfferRowVersion");
      var values =
          new MapSqlParameterSource()
              .addValue("id", submission)
              .addValue("version", version)
              .addValue("no", versionNo)
              .addValue("base", base)
              .addValue("payload", json.writeValueAsString(data.path("payload")));
      jdbc.update(
          "INSERT INTO"
              + " catalog_submission_versions(version_id,submission_id,version_no,payload,base_offer_row_version)"
              + " VALUES(:version,:id,:no,cast(:payload as jsonb),:base) ON CONFLICT(version_id) DO"
              + " NOTHING",
          values);
      if (!Boolean.TRUE.equals(
          jdbc.queryForObject(
              "SELECT submission_id=:id AND version_no=:no AND payload=cast(:payload as jsonb) AND"
                  + " base_offer_row_version IS NOT DISTINCT FROM cast(:base as int) FROM"
                  + " catalog_submission_versions WHERE version_id=:version",
              values,
              Boolean.class))) throw new IllegalArgumentException("VERSION_CONTENT_CONFLICT");
      status = "VERSION_RECEIVED";
    }
    if (sequence <= current) return;
    params
        .addValue("sequence", sequence)
        .addValue("version", version)
        .addValue("no", versionNo)
        .addValue("status", status)
        .addValue("row", rowVersion)
        .addValue("correlation", correlation);
    jdbc.update(
        """
        UPDATE catalog_submission_state SET aggregate_sequence=:sequence,version_id=:version,version_no=:no,
        status=:status,row_version=:row,correlation_id=:correlation,publication_outcome=NULL,publication_error=NULL
        WHERE submission_id=:id
        """,
        params);
  }

  private void saveMetadata(UUID submission, JsonNode data) {
    UUID version = uuid(data, "versionId");
    int no = number(data, "versionNo", 1);
    String type = ReviewedSubmission.Type.valueOf(text(data, "type")).name();
    String zone = text(data, "resolvedTimezone");
    if (zone.length() > 64 || !ZoneId.getAvailableZoneIds().contains(zone))
      throw new IllegalArgumentException("metadata.timezone");
    Integer base = optionalNumber(data, "basePlaceRowVersion");
    if (!type.equals("CHANGE") && base != null)
      throw new IllegalArgumentException("metadata.basePlaceRowVersion");
    var params =
        new MapSqlParameterSource()
            .addValue("version", version)
            .addValue("id", submission)
            .addValue("no", no)
            .addValue("type", type)
            .addValue("zone", zone)
            .addValue("base", base);
    jdbc.update(
        "INSERT INTO"
            + " catalog_submission_metadata(version_id,submission_id,version_no,submission_type,resolved_timezone,base_place_row_version)"
            + " VALUES(:version,:id,:no,:type,:zone,:base) ON CONFLICT(version_id) DO NOTHING",
        params);
    if (!Boolean.TRUE.equals(
        jdbc.queryForObject(
            "SELECT submission_id=:id AND version_no=:no AND submission_type=:type AND"
                + " resolved_timezone=:zone AND base_place_row_version IS NOT DISTINCT FROM"
                + " cast(:base as int) FROM catalog_submission_metadata WHERE version_id=:version",
            params,
            Boolean.class))) throw new IllegalArgumentException("METADATA_CONTENT_CONFLICT");
  }

  private static void requireFields(JsonNode node, Set<String> fields) {
    if (!node.isObject()
        || !node.properties().stream()
            .map(Map.Entry::getKey)
            .collect(java.util.stream.Collectors.toSet())
            .equals(fields)) throw new IllegalArgumentException("metadata.fields");
  }

  private static String text(JsonNode node, String name) {
    if (!node.path(name).isString() || node.path(name).asText().isBlank())
      throw new IllegalArgumentException(name);
    return node.path(name).asText();
  }

  private static UUID uuid(JsonNode node, String name) {
    String value = text(node, name);
    UUID id = UUID.fromString(value);
    if (!id.toString().equalsIgnoreCase(value)) throw new IllegalArgumentException(name);
    return id;
  }

  private static long sequence(JsonNode event) {
    var value = event.path("aggregateSequence");
    if (!value.isIntegralNumber() || !value.canConvertToLong() || value.asLong() < 1)
      throw new IllegalArgumentException("aggregateSequence");
    return value.asLong();
  }

  private static int number(JsonNode node, String name, int minimum) {
    var value = node.path(name);
    if (!value.isIntegralNumber() || !value.canConvertToInt() || value.asInt() < minimum)
      throw new IllegalArgumentException(name);
    return value.asInt();
  }

  private static Integer optionalNumber(JsonNode node, String name) {
    return node.path(name).isMissingNode() || node.path(name).isNull()
        ? null
        : number(node, name, 0);
  }
}
