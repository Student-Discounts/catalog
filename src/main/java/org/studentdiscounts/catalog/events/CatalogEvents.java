package org.studentdiscounts.catalog.events;

import java.sql.Timestamp;
import java.time.Clock;
import java.util.*;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.studentdiscounts.catalog.model.*;
import org.studentdiscounts.catalog.persistence.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

@Service
public class CatalogEvents {
  public static final String TOPIC = "catalog.search.events";
  private final CatalogRepository repository;
  private final NamedParameterJdbcTemplate jdbc;
  private final ObjectMapper json;
  private final Clock clock;

  public CatalogEvents(
      CatalogRepository repository,
      NamedParameterJdbcTemplate jdbc,
      ObjectMapper json,
      Clock clock) {
    this.repository = repository;
    this.jdbc = jdbc;
    this.json = json;
    this.clock = clock;
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public void record(UUID placeId, Collection<UUID> changedOffers) {
    Place place = repository.lockPlace(placeId).orElseThrow();
    ObjectNode data = json.createObjectNode();
    ObjectNode placeSnapshot = placeSnapshot(place);
    ObjectNode changedPlace = advance("places", placeId, placeSnapshot);
    if (changedPlace != null) data.set("place", changedPlace);
    var offers = json.createArrayNode();
    Set<UUID> ids = new TreeSet<>(changedOffers);
    if (changedPlace != null && changedPlace.path("revision").asInt() == 0) {
      ids.addAll(
          jdbc.query(
              "SELECT id FROM offers WHERE place_id=:id ORDER BY id",
              Map.of("id", placeId),
              (rs, n) -> rs.getObject(1, UUID.class)));
    }
    for (UUID id : ids) {
      Offer offer = repository.lockOffer(id).orElseThrow();
      if (!offer.placeId().equals(placeId)) throw new IllegalArgumentException("offer.placeId");
      ObjectNode changed = advance("offers", id, offerSnapshot(offer));
      if (changed != null) offers.add(changed);
    }
    if (!offers.isEmpty()) data.set("offers", offers);
    if (data.isEmpty()) return;
    UUID eventId = CatalogIds.newId();
    var now = clock.instant();
    ObjectNode event =
        json.createObjectNode()
            .put("eventId", eventId.toString())
            .put("eventType", "CatalogChanged")
            .put("occurredAt", now.toString())
            .put("placeId", placeId.toString());
    event.set("data", data);
    jdbc.update(
        """
        INSERT INTO catalog_outbox(id,topic,message_key,payload,occurred_at)
        VALUES(:id,:topic,:key,cast(:payload as jsonb),:now)
        """,
        Map.of(
            "id",
            eventId,
            "topic",
            TOPIC,
            "key",
            placeId,
            "payload",
            json.writeValueAsString(event),
            "now",
            Timestamp.from(now)));
  }

  private ObjectNode advance(String table, UUID id, ObjectNode snapshot) {
    var previous =
        jdbc.queryForObject(
            "SELECT event_revision,event_snapshot::text,event_snapshot=cast(:snapshot as jsonb)"
                + " FROM "
                + table
                + " WHERE id=:id FOR UPDATE",
            Map.of("id", id, "snapshot", json.writeValueAsString(snapshot)),
            (rs, n) -> new Previous(rs.getInt(1), rs.getString(2), rs.getBoolean(3)));
    if (previous.snapshot() != null && previous.equal()) return null;
    int revision =
        previous.snapshot() == null
            ? previous.revision()
            : Math.incrementExact(previous.revision());
    jdbc.update(
        "UPDATE "
            + table
            + " SET event_revision=:revision,event_snapshot=cast(:snapshot as jsonb) WHERE id=:id",
        Map.of("revision", revision, "snapshot", json.writeValueAsString(snapshot), "id", id));
    return snapshot.deepCopy().put("revision", revision);
  }

  private ObjectNode placeSnapshot(Place place) {
    Category category = repository.category(place.categoryCode()).orElseThrow();
    ObjectNode node =
        json.createObjectNode()
            .put("placeName", place.name())
            .put("city", place.city())
            .put("addressLine", place.addressLine())
            .put("categoryCode", category.code())
            .put("categoryName", category.name())
            .put("timezone", place.timezone().getId());
    node.putObject("location")
        .put("latitude", place.location().lat())
        .put("longitude", place.location().lon());
    return node;
  }

  private ObjectNode offerSnapshot(Offer offer) {
    var data = offer.data();
    ObjectNode node =
        json.createObjectNode()
            .put("offerId", offer.id().toString())
            .put("status", offer.status().name())
            .put("discountType", data.discountType().name())
            .put("lastVerifiedAt", offer.lastVerifiedAt().toString());
    node.set("discountValue", json.valueToTree(data.discountValue()));
    node.set("validFrom", json.valueToTree(data.validFrom()));
    node.set("validUntil", json.valueToTree(data.validUntil()));
    node.set("schedule", groupedSchedule(data.schedule()));
    return node;
  }

  private JsonNode groupedSchedule(List<ScheduleInterval> schedule) {
    Map<Integer, List<Interval>> days = new TreeMap<>();
    schedule.stream()
        .sorted(
            Comparator.comparingInt(ScheduleInterval::dayOfWeek)
                .thenComparing(i -> i.timeFrom().toString()))
        .forEach(i ->
            days.computeIfAbsent(i.dayOfWeek(), ignored -> new ArrayList<>())
                .add(new Interval(i.timeFrom().toString(), i.timeUntil().toString())));
    Map<List<Interval>, List<Integer>> groups = new LinkedHashMap<>();
    days.forEach((day, intervals) ->
        groups.computeIfAbsent(List.copyOf(intervals), ignored -> new ArrayList<>()).add(day));
    var result = json.createArrayNode();
    groups.forEach((intervals, groupDays) -> {
      var group = result.addObject();
      group.set("daysOfWeek", json.valueToTree(groupDays));
      group.set("intervals", json.valueToTree(intervals));
    });
    return result;
  }

  private record Interval(String timeFrom, String timeUntil) {}

  private record Previous(int revision, String snapshot, boolean equal) {}
}
