package org.studentdiscounts.catalog.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.studentdiscounts.catalog.model.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

@Repository
public class CatalogRepository {
  private static final String PLACE_COLUMNS =
      """
      id, name, city, address_line, address_normalized, category_code, timezone, organization_id,
      ST_Y(location::geometry) lat, ST_X(location::geometry) lon,
      row_version, created_at, updated_at
      """;
  private final NamedParameterJdbcTemplate jdbc;
  private final ObjectMapper json;

  public CatalogRepository(NamedParameterJdbcTemplate jdbc, ObjectMapper json) {
    this.jdbc = jdbc;
    this.json = json;
  }

  public void saveCategory(Category category) {
    jdbc.update(
        """
        INSERT INTO categories(code,name,sort_order,is_active) VALUES(:code,:name,:sort,:active)
        ON CONFLICT(code) DO UPDATE SET name=excluded.name, sort_order=excluded.sort_order,
        is_active=excluded.is_active
        """,
        Map.of(
            "code",
            category.code(),
            "name",
            category.name(),
            "sort",
            category.sortOrder(),
            "active",
            category.isActive()));
  }

  public Optional<Category> category(String code) {
    return jdbc
        .query(
            "SELECT * FROM categories WHERE code=:code",
            Map.of("code", code),
            (rs, n) ->
                new Category(
                    rs.getString("code"),
                    rs.getString("name"),
                    rs.getInt("sort_order"),
                    rs.getBoolean("is_active")))
        .stream()
        .findFirst();
  }

  public boolean lockActiveCategory(String code) {
    return jdbc
        .query(
            "SELECT is_active FROM categories WHERE code=:code FOR SHARE",
            Map.of("code", code),
            (rs, n) -> rs.getBoolean(1))
        .stream()
        .findFirst()
        .orElse(false);
  }

  public Optional<Place> place(UUID id) {
    return selectPlace(id, false);
  }

  public Optional<Place> lockPlace(UUID id) {
    return selectPlace(id, true);
  }

  private Optional<Place> selectPlace(UUID id, boolean lock) {
    return jdbc
        .query(
            "SELECT " + PLACE_COLUMNS + " FROM places WHERE id=:id" + (lock ? " FOR UPDATE" : ""),
            Map.of("id", id),
            this::placeRow)
        .stream()
        .findFirst();
  }

  public Place insertPlace(PlaceData data, ZoneId zone, Instant now) {
    UUID id = CatalogIds.newId();
    var params = placeParams(data, zone, now).addValue("id", id);
    jdbc.update(
        """
        INSERT INTO places(id,name,city,address_line,address_normalized,location,timezone,
        category_code,created_at,updated_at)
        VALUES(:id,:name,:city,:address,:normalized,ST_SetSRID(ST_MakePoint(:lon,:lat),4326)::geography,
        :zone,:category,:now,:now)
        """,
        params);
    return place(id).orElseThrow();
  }

  public Place updatePlace(PlaceData data, ZoneId zone, int expected, Instant now) {
    int count =
        jdbc.update(
            """
            UPDATE places SET name=:name, city=:city, address_line=:address, address_normalized=:normalized,
            location=ST_SetSRID(ST_MakePoint(:lon,:lat),4326)::geography, timezone=:zone,
            category_code=:category, row_version=row_version+1, updated_at=:now
            WHERE id=:id AND row_version=:expected
            """,
            placeParams(data, zone, now).addValue("id", data.id()).addValue("expected", expected));
    if (count != 1)
      throw new org.studentdiscounts.catalog.publication.CatalogConflict("PLACE_VERSION_CONFLICT");
    return place(data.id()).orElseThrow();
  }

  public Optional<Offer> offer(UUID id) {
    return selectOffer(id, false);
  }

  public Optional<Offer> lockOffer(UUID id) {
    return selectOffer(id, true);
  }

  private Optional<Offer> selectOffer(UUID id, boolean lock) {
    return jdbc
        .query(
            "SELECT * FROM offers WHERE id=:id" + (lock ? " FOR UPDATE" : ""),
            Map.of("id", id),
            (rs, n) -> offerRow(rs))
        .stream()
        .findFirst();
  }

  public UUID insertOffer(UUID placeId, OfferData data, UUID versionId, Instant now) {
    UUID id = CatalogIds.newId();
    jdbc.update(
        """
        INSERT INTO offers(id,place_id,status,conditions,discount_type,discount_value,source_type,
        source_url,source_note,valid_from,valid_until,last_verified_at,approved_version_id,created_at,updated_at)
        VALUES(:id,:place,'ACTIVE',:conditions,cast(:discountType as discount_type),:value,
        cast(:sourceType as source_type),:url,:note,:from,:until,:now,:version,:now,:now)
        """,
        offerParams(data, versionId, now).addValue("id", id).addValue("place", placeId));
    replaceSchedule(id, data.schedule());
    return id;
  }

  public void updateOffer(UUID id, OfferData data, UUID versionId, int expected, Instant now) {
    int count =
        jdbc.update(
            """
            UPDATE offers SET conditions=:conditions, discount_type=cast(:discountType as discount_type),
            discount_value=:value, source_type=cast(:sourceType as source_type), source_url=:url,
            source_note=:note, valid_from=:from, valid_until=:until, last_verified_at=:now,
            approved_version_id=:version, row_version=row_version+1, updated_at=:now
            WHERE id=:id AND row_version=:expected AND status='ACTIVE'
            """,
            offerParams(data, versionId, now).addValue("id", id).addValue("expected", expected));
    if (count != 1)
      throw new org.studentdiscounts.catalog.publication.CatalogConflict(
          "CATALOG_VERSION_CONFLICT");
    replaceSchedule(id, data.schedule());
  }

  public void saveSnapshot(UUID offerId, CardSnapshot snapshot) {
    jdbc.update(
        """
        INSERT INTO catalog_offer_snapshots(offer_id,payload) VALUES(:id,cast(:payload as jsonb))
        ON CONFLICT(offer_id) DO UPDATE SET payload=excluded.payload
        """,
        Map.of("id", offerId, "payload", json.writeValueAsString(snapshot)));
  }

  public JsonNode payload(UUID offerId) {
    ObjectNode snapshot =
        (ObjectNode)
            jdbc.queryForObject(
                "SELECT payload::text FROM catalog_offer_snapshots WHERE offer_id=:id",
                Map.of("id", offerId),
                (rs, n) -> json.readTree(rs.getString(1)));
    Offer offer = offer(offerId).orElseThrow();
    snapshot.set("place", json.valueToTree(place(offer.placeId()).orElseThrow().data()));
    return snapshot;
  }

  private void replaceSchedule(UUID offerId, List<ScheduleInterval> schedule) {
    jdbc.update("DELETE FROM offer_schedule WHERE offer_id=:id", Map.of("id", offerId));
    for (ScheduleInterval interval : schedule) {
      jdbc.update(
          """
          INSERT INTO offer_schedule(id,offer_id,day_of_week,time_from,time_until)
          VALUES(:id,:offer,:day,cast(:start as time),cast(:end as time))
          """,
          Map.of(
              "id",
              CatalogIds.newId(),
              "offer",
              offerId,
              "day",
              interval.dayOfWeek(),
              "start",
              interval.timeFrom().toString(),
              "end",
              interval.timeUntil().toString()));
    }
  }

  private Place placeRow(ResultSet rs, int n) throws SQLException {
    return new Place(
        rs.getObject("id", UUID.class),
        rs.getString("name"),
        rs.getString("city"),
        rs.getString("address_line"),
        rs.getString("address_normalized"),
        new Location(rs.getDouble("lat"), rs.getDouble("lon")),
        rs.getString("category_code"),
        ZoneId.of(rs.getString("timezone")),
        rs.getObject("organization_id", UUID.class),
        rs.getInt("row_version"),
        rs.getTimestamp("created_at").toInstant(),
        rs.getTimestamp("updated_at").toInstant());
  }

  private Offer offerRow(ResultSet rs) throws SQLException {
    UUID id = rs.getObject("id", UUID.class);
    var intervals =
        jdbc.query(
            """
            SELECT day_of_week,time_from::text,time_until::text FROM offer_schedule
            WHERE offer_id=:id ORDER BY day_of_week,time_from
            """,
            Map.of("id", id),
            (row, n) ->
                new ScheduleInterval(
                    row.getInt(1),
                    TimeOfDay.parse(row.getString(2).substring(0, 5)),
                    TimeOfDay.parse(row.getString(3).substring(0, 5))));
    Source source =
        rs.getString("source_type") == null
            ? null
            : new Source(
                SourceType.valueOf(rs.getString("source_type")),
                rs.getString("source_url"),
                rs.getString("source_note"));
    var data =
        new OfferData(
            id,
            rs.getString("conditions"),
            DiscountType.valueOf(rs.getString("discount_type")),
            rs.getBigDecimal("discount_value"),
            source,
            rs.getObject("valid_from", java.time.LocalDate.class),
            rs.getObject("valid_until", java.time.LocalDate.class),
            intervals);
    return new Offer(
        id,
        rs.getObject("place_id", UUID.class),
        OfferStatus.valueOf(rs.getString("status")),
        data,
        rs.getObject("approved_version_id", UUID.class),
        rs.getTimestamp("last_verified_at").toInstant(),
        rs.getInt("row_version"),
        rs.getTimestamp("created_at").toInstant(),
        rs.getTimestamp("updated_at").toInstant());
  }

  private MapSqlParameterSource placeParams(PlaceData data, ZoneId zone, Instant now) {
    return new MapSqlParameterSource()
        .addValue("name", data.name())
        .addValue("city", data.city())
        .addValue("address", data.addressLine())
        .addValue("normalized", normalize(data.addressLine()))
        .addValue("lat", data.location().lat())
        .addValue("lon", data.location().lon())
        .addValue("category", data.categoryCode())
        .addValue("zone", zone.getId())
        .addValue("now", java.sql.Timestamp.from(now));
  }

  private MapSqlParameterSource offerParams(OfferData data, UUID version, Instant now) {
    return new MapSqlParameterSource()
        .addValue("conditions", data.conditions())
        .addValue("discountType", data.discountType().name())
        .addValue("value", data.discountValue())
        .addValue("sourceType", data.source() == null ? null : data.source().type().name())
        .addValue("url", data.source() == null ? null : data.source().url())
        .addValue("note", data.source() == null ? null : data.source().note())
        .addValue("from", data.validFrom())
        .addValue("until", data.validUntil())
        .addValue("version", version)
        .addValue("now", java.sql.Timestamp.from(now));
  }

  private static String normalize(String address) {
    return java.text.Normalizer.normalize(address, java.text.Normalizer.Form.NFKC)
        .toLowerCase(java.util.Locale.ROOT)
        .strip()
        .replaceAll("\\s+", " ");
  }
}
