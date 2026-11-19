package org.studentdiscounts.catalog;

import static org.assertj.core.api.Assertions.*;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.*;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.studentdiscounts.catalog.model.*;
import org.studentdiscounts.catalog.persistence.*;
import org.studentdiscounts.catalog.publication.*;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class CatalogPersistenceTest {
  static final PostgreSQLContainer DATABASE =
      new PostgreSQLContainer(
              DockerImageName.parse(
                      System.getProperty("catalog.test.image", "postgis/postgis:17-3.5"))
                  .asCompatibleSubstituteFor("postgres"))
          .withStartupTimeout(Duration.ofMinutes(3))
          .withCreateContainerCmdModifier(command -> command.withPlatform("linux/amd64"));

  @DynamicPropertySource
  static void database(DynamicPropertyRegistry registry) {
    DATABASE.start();
    registry.add("spring.datasource.url", DATABASE::getJdbcUrl);
    registry.add("spring.datasource.username", DATABASE::getUsername);
    registry.add("spring.datasource.password", DATABASE::getPassword);
  }

  @AfterAll
  static void shutdown() {
    DATABASE.stop();
  }

  @TestConfiguration
  static class FixedTime {
    @Bean
    @Primary
    Clock testClock() {
      return Clock.fixed(Instant.parse("2026-11-06T10:00:00Z"), ZoneOffset.UTC);
    }
  }

  @Autowired CatalogRepository catalog;
  @Autowired CatalogPublicationService publications;
  @Autowired NamedParameterJdbcTemplate jdbc;
  @Autowired ObjectMapper json;
  @LocalServerPort int port;

  @BeforeEach
  void clean() {
    jdbc.getJdbcTemplate()
        .execute(
            "TRUNCATE"
                + " catalog_outbox,catalog_offer_snapshots,catalog_publications,offer_schedule,offers,places,categories"
                + " CASCADE");
    catalog.saveCategory(new Category("cafe", "Кафе", 1, true));
  }

  PlaceData place() {
    return new PlaceData(
        null,
        "Кофейня «Зерно»",
        "Санкт-Петербург",
        "Кронверкский пр., 49",
        new Location(59.9571, 30.3083),
        "cafe");
  }

  OfferData offer(String conditions) {
    return new OfferData(
        null,
        conditions,
        DiscountType.PERCENT,
        new BigDecimal("15"),
        new Source(SourceType.WEBSITE, "https://example.com/students", "Проверено по объявлению"),
        LocalDate.of(2026, 11, 1),
        LocalDate.of(2026, 12, 31),
        List.of(new ScheduleInterval(5, TimeOfDay.parse("22:00"), TimeOfDay.parse("24:00"))));
  }

  ReviewedSubmission reviewed(
      ReviewedSubmission.Type type,
      PlaceData place,
      List<OfferData> offers,
      List<Attachment> assets,
      Integer baseOfferVersion,
      Integer basePlaceVersion) {
    return new ReviewedSubmission(
        UUID.randomUUID(),
        UUID.randomUUID(),
        1,
        4,
        type,
        new CardSnapshot(place, offers, assets),
        "Europe/Moscow",
        baseOfferVersion,
        basePlaceVersion,
        "test-correlation");
  }

  ReviewedSubmission newPlace(int count) {
    return reviewed(
        ReviewedSubmission.Type.NEW_PLACE,
        place(),
        java.util.stream.IntStream.range(0, count).mapToObj(i -> offer("Скидка " + i)).toList(),
        List.of(),
        null,
        null);
  }

  int count(String table) {
    return jdbc.getJdbcTemplate().queryForObject("SELECT count(*) FROM " + table, Integer.class);
  }

  @Test
  void migratesRealPostgres17AndPostgis() {
    assertThat(jdbc.getJdbcTemplate().queryForObject("SHOW server_version_num", Integer.class))
        .isBetween(170000, 179999);
    assertThat(jdbc.getJdbcTemplate().queryForObject("SELECT postgis_lib_version()", String.class))
        .startsWith("3.5");
    assertThat(
            jdbc.getJdbcTemplate()
                .queryForObject(
                    "SELECT count(*) FROM flyway_schema_history WHERE success", Integer.class))
        .isEqualTo(2);
  }

  @Test
  void storesOnePlaceSeveralOffersAndExactPostgisCoordinates() {
    var command = newPlace(2);
    var result = publications.publish(command);
    assertThat(result.placeId().version()).isEqualTo(7);
    assertThat(result.offerIds()).hasSize(2).doesNotHaveDuplicates();
    assertThat(count("places")).isEqualTo(1);
    assertThat(count("offers")).isEqualTo(2);
    assertThat(count("offer_schedule")).isEqualTo(2);
    Place place = catalog.place(result.placeId()).orElseThrow();
    assertThat(place.location()).isEqualTo(command.payload().place().location());
    assertThat(place.timezone()).isEqualTo(ZoneId.of("Europe/Moscow"));
    assertThat(place.organizationId()).isNull();
    assertThat(place.rowVersion()).isZero();
    Offer first = catalog.offer(result.offerIds().getFirst()).orElseThrow();
    assertThat(first.approvedVersionId()).isEqualTo(command.versionId());
    assertThat(first.lastVerifiedAt()).isEqualTo(Instant.parse("2026-11-06T10:00:00Z"));
    assertThat(first.data().schedule().getFirst().timeUntil().toString()).isEqualTo("24:00");
    assertThat(
            jdbc.queryForObject(
                "SELECT"
                    + " ST_DWithin(location,ST_SetSRID(ST_MakePoint(:lon,:lat),4326)::geography,2000)"
                    + " FROM places WHERE id=:id",
                Map.of("lon", 30.3083, "lat", 59.9571, "id", result.placeId()),
                Boolean.class))
        .isTrue();
  }

  @Test
  void repeatedPublicationReturnsOriginalIdsAndDoesNotCreateAnotherEvent() {
    var command = newPlace(2);
    var first = publications.publish(command);
    var retry =
        new ReviewedSubmission(
            command.submissionId(),
            command.versionId(),
            command.versionNo(),
            command.expectedSubmissionRowVersion(),
            command.type(),
            command.payload(),
            command.resolvedTimezone(),
            null,
            null,
            "different-correlation");
    assertThat(publications.publish(retry)).isEqualTo(first);
    assertThat(count("places")).isEqualTo(1);
    assertThat(count("offers")).isEqualTo(2);
    assertThat(count("catalog_outbox")).isEqualTo(1);
  }

  @Test
  void concurrentDuplicatePublicationIsSerializedByDatabase() throws Exception {
    var command = newPlace(2);
    try (var executor = Executors.newFixedThreadPool(2)) {
      var start = new CountDownLatch(1);
      Callable<PublicationResult> publish =
          () -> {
            start.await();
            return publications.publish(command);
          };
      var one = executor.submit(publish);
      var two = executor.submit(publish);
      start.countDown();
      assertThat(one.get(20, TimeUnit.SECONDS)).isEqualTo(two.get(20, TimeUnit.SECONDS));
    }
    assertThat(count("offers")).isEqualTo(2);
    assertThat(count("catalog_publications")).isEqualTo(1);
    assertThat(count("catalog_outbox")).isEqualTo(1);
  }

  @Test
  void sameVersionCannotBeReusedWithDifferentContentOrIdentity() {
    var original = newPlace(1);
    publications.publish(original);
    var changed =
        new ReviewedSubmission(
            original.submissionId(),
            original.versionId(),
            1,
            4,
            original.type(),
            new CardSnapshot(place(), List.of(offer("Другие условия")), List.of()),
            original.resolvedTimezone(),
            null,
            null,
            original.correlationId());
    assertThatThrownBy(() -> publications.publish(changed))
        .isInstanceOf(CatalogConflict.class)
        .hasMessage("VERSION_CONTENT_CONFLICT");
    var conflicting =
        new ReviewedSubmission(
            original.submissionId(),
            UUID.randomUUID(),
            1,
            4,
            original.type(),
            original.payload(),
            original.resolvedTimezone(),
            null,
            null,
            original.correlationId());
    assertThatThrownBy(() -> publications.publish(conflicting))
        .isInstanceOf(CatalogConflict.class)
        .hasMessage("VERSION_IDENTITY_CONFLICT");
    assertThat(count("offers")).isEqualTo(1);
  }

  @Test
  void newOfferReusesExistingPlaceAndDoesNotOverwriteIt() {
    var first = publications.publish(newPlace(1));
    Place existing = catalog.place(first.placeId()).orElseThrow();
    var result =
        publications.publish(
            reviewed(
                ReviewedSubmission.Type.NEW_OFFER,
                existing.data(),
                List.of(offer("Вторая акция")),
                List.of(),
                null,
                null));
    assertThat(result.placeId()).isEqualTo(first.placeId());
    assertThat(count("places")).isEqualTo(1);
    assertThat(count("offers")).isEqualTo(2);
    assertThat(catalog.place(first.placeId()).orElseThrow().rowVersion()).isZero();
    PlaceData changed =
        new PlaceData(
            existing.id(),
            "Другое имя",
            existing.city(),
            existing.addressLine(),
            existing.location(),
            existing.categoryCode());
    assertThatThrownBy(() ->
            publications.publish(
                reviewed(
                    ReviewedSubmission.Type.NEW_OFFER,
                    changed,
                    List.of(offer("Нельзя менять место")),
                    List.of(),
                    null,
                    null)))
        .hasMessage("PLACE_VERSION_REQUIRED");
    assertThat(count("offers")).isEqualTo(2);
  }

  @Test
  void changeUpdatesSameOfferAndReplacesScheduleWithVersionCheck() {
    var first = publications.publish(newPlace(1));
    UUID id = first.offerIds().getFirst();
    Place existing = catalog.place(first.placeId()).orElseThrow();
    var changed =
        new OfferData(
            id,
            "20% по студенческому",
            DiscountType.PERCENT,
            new BigDecimal("20"),
            new Source(SourceType.PERSONAL_VISIT, null, "Листовка"),
            null,
            null,
            List.of());
    var command =
        reviewed(
            ReviewedSubmission.Type.CHANGE, existing.data(), List.of(changed), List.of(), 0, null);
    assertThat(publications.publish(command).offerIds()).containsExactly(id);
    Offer updated = catalog.offer(id).orElseThrow();
    assertThat(updated.rowVersion()).isEqualTo(1);
    assertThat(updated.data().conditions()).isEqualTo(changed.conditions());
    assertThat(updated.approvedVersionId()).isEqualTo(command.versionId());
    assertThat(count("offer_schedule")).isZero();
    var stale =
        reviewed(
            ReviewedSubmission.Type.CHANGE, existing.data(), List.of(changed), List.of(), 0, null);
    assertThatThrownBy(() -> publications.publish(stale)).hasMessage("CATALOG_VERSION_CONFLICT");
    assertThat(count("catalog_publications")).isEqualTo(2);
    assertThat(count("catalog_outbox")).isEqualTo(2);
  }

  @Test
  void failedChangeRollsBackPlaceUpdateReceiptAndOutbox() {
    var first = publications.publish(newPlace(1));
    var existing = catalog.place(first.placeId()).orElseThrow();
    var changedPlace =
        new PlaceData(
            existing.id(),
            "Новое имя",
            existing.city(),
            existing.addressLine(),
            existing.location(),
            existing.categoryCode());
    var command =
        reviewed(
            ReviewedSubmission.Type.CHANGE,
            changedPlace,
            List.of(offer("Правка").identified(first.offerIds().getFirst())),
            List.of(),
            9,
            0);
    assertThatThrownBy(() -> publications.publish(command)).hasMessage("CATALOG_VERSION_CONFLICT");
    assertThat(catalog.place(first.placeId()).orElseThrow()).isEqualTo(existing);
    assertThat(count("catalog_publications")).isEqualTo(1);
    assertThat(count("catalog_outbox")).isEqualTo(1);
  }

  @Test
  void placeChangesRequireTheirOwnExpectedVersion() {
    var first = publications.publish(newPlace(1));
    var existing = catalog.place(first.placeId()).orElseThrow();
    var changed =
        new PlaceData(
            existing.id(),
            "Новое название",
            existing.city(),
            existing.addressLine(),
            existing.location(),
            existing.categoryCode());
    var data = offer("Правка").identified(first.offerIds().getFirst());
    assertThatThrownBy(() ->
            publications.publish(
                reviewed(
                    ReviewedSubmission.Type.CHANGE, changed, List.of(data), List.of(), 0, null)))
        .hasMessage("PLACE_VERSION_REQUIRED");
    publications.publish(
        reviewed(ReviewedSubmission.Type.CHANGE, changed, List.of(data), List.of(), 0, 0));
    assertThat(catalog.place(existing.id()).orElseThrow().rowVersion()).isEqualTo(1);
  }

  @Test
  void proofOnlyOffersRetainPerOfferAttachmentsInSnapshots() {
    var one = offer("Первая");
    var two = new OfferData(null, "Вторая", null, null, null, null, null, List.of());
    UUID proofId = UUID.randomUUID();
    var result =
        publications.publish(
            reviewed(
                ReviewedSubmission.Type.NEW_PLACE,
                place(),
                List.of(one, two),
                List.of(new Attachment(proofId, Attachment.Purpose.PROOF, 1)),
                null,
                null));
    var payload = catalog.payload(result.offerIds().get(1));
    assertThat(payload.path("attachments").get(0).path("mediaAssetId").asText())
        .isEqualTo(proofId.toString());
    assertThat(payload.path("attachments").get(0).path("offerIndex").asInt()).isZero();
    assertThat(catalog.payload(result.offerIds().getFirst()).path("attachments").isEmpty())
        .isTrue();
  }

  @Test
  void inactiveCategoryAndExpiredOfferDoNotCreatePartialRecords() {
    catalog.saveCategory(new Category("cafe", "Кафе", 1, false));
    assertThatThrownBy(() -> publications.publish(newPlace(1))).hasMessage("CATEGORY_NOT_ACTIVE");
    assertThat(count("catalog_publications")).isZero();
    assertThat(count("places")).isZero();
    assertThat(count("catalog_outbox")).isZero();
    catalog.saveCategory(new Category("cafe", "Кафе", 1, true));
    var expired =
        new OfferData(
            null,
            "Истекла",
            null,
            null,
            new Source(SourceType.OTHER, null, "Листовка"),
            null,
            LocalDate.of(2026, 11, 5),
            List.of());
    assertThatThrownBy(() ->
            publications.publish(
                reviewed(
                    ReviewedSubmission.Type.NEW_PLACE,
                    place(),
                    List.of(expired),
                    List.of(),
                    null,
                    null)))
        .hasMessage("OFFER_ALREADY_EXPIRED");
    assertThat(count("offers")).isZero();
  }

  @Test
  void databaseRejectsCategoryRenamingInvalidTimezoneAndOverlappingIntervals() {
    var result = publications.publish(newPlace(1));
    UUID id = result.offerIds().getFirst();
    assertThatThrownBy(() ->
            jdbc.getJdbcTemplate().update("UPDATE categories SET code='coffee' WHERE code='cafe'"))
        .isInstanceOf(DataIntegrityViolationException.class);
    assertThatThrownBy(() ->
            jdbc.update(
                "UPDATE places SET timezone='Europe/Unknown' WHERE id=:id",
                Map.of("id", result.placeId())))
        .isInstanceOf(DataIntegrityViolationException.class);
    for (String values : List.of("8,'10:00','12:00'", "1,'14:00','10:00'", "5,'23:00','24:00'")) {
      assertThatThrownBy(() ->
              jdbc.update(
                  "INSERT INTO offer_schedule(id,offer_id,day_of_week,time_from,time_until)"
                      + " VALUES(:id,:offer,"
                      + values
                      + ")",
                  Map.of("id", UUID.randomUUID(), "offer", id)))
          .isInstanceOf(DataIntegrityViolationException.class);
    }
    jdbc.update(
        "INSERT INTO offer_schedule(id,offer_id,day_of_week,time_from,time_until)"
            + " VALUES(:id,:offer,5,'20:00','22:00')",
        Map.of("id", UUID.randomUUID(), "offer", id));
    assertThat(catalog.offer(id).orElseThrow().data().schedule()).hasSize(2);
  }

  @Test
  void internalReadApiMatchesSubmissionConsumerFields() throws Exception {
    var result = publications.publish(newPlace(1));
    var category = get("/catalog/v1/categories/cafe", 200);
    assertThat(category.path("code").asText()).isEqualTo("cafe");
    assertThat(category.path("isActive").asBoolean()).isTrue();
    var place = get("/catalog/v1/places/" + result.placeId(), 200);
    assertThat(place.path("timezone").asText()).isEqualTo("Europe/Moscow");
    assertThat(place.path("rowVersion").asInt()).isZero();
    assertThat(place.path("location").path("lon").asDouble()).isEqualTo(30.3083);
    var offer = get("/catalog/v1/offers/" + result.offerIds().getFirst(), 200);
    assertThat(offer.path("placeId").asText()).isEqualTo(result.placeId().toString());
    assertThat(offer.path("status").asText()).isEqualTo("ACTIVE");
    assertThat(
            offer
                .path("payload")
                .path("offers")
                .get(0)
                .path("schedule")
                .get(0)
                .path("timeUntil")
                .asText())
        .isEqualTo("24:00");
    get("/catalog/v1/places/" + UUID.randomUUID(), 404);
    get("/catalog/v1/offers/not-a-uuid", 400);
  }

  @Test
  void outboxUsesSubmissionWorkflowContractAndIsCommittedWithCard() {
    var command = newPlace(2);
    var result = publications.publish(command);
    var event =
        jdbc.getJdbcTemplate()
            .queryForObject("SELECT payload::text FROM catalog_outbox", String.class);
    JsonNode message = json.readTree(event);
    assertThat(message.path("eventType").asText()).isEqualTo("OfferPublished");
    assertThat(message.path("schemaVersion").asInt()).isEqualTo(1);
    assertThat(message.path("producer").asText()).isEqualTo("catalog");
    assertThat(message.path("data").path("expectedRowVersion").asInt()).isEqualTo(4);
    assertThat(message.path("data").path("versionId").asText())
        .isEqualTo(command.versionId().toString());
    assertThat(message.path("data").path("offerIds").size()).isEqualTo(result.offerIds().size());
    assertThat(
            jdbc.getJdbcTemplate().queryForObject("SELECT topic FROM catalog_outbox", String.class))
        .isEqualTo("submission.workflow.v1");
    assertThat(
            jdbc.getJdbcTemplate()
                .queryForObject("SELECT published_at FROM catalog_outbox", Timestamp.class))
        .isNull();
  }

  @Test
  void serializesRealContractExamplesWithoutNullSourceMembers() throws Exception {
    var command = newPlace(1);
    var result = publications.publish(command);
    JsonNode payload = catalog.payload(result.offerIds().getFirst());
    assertThat(payload.path("offers").get(0).path("validFrom").asText()).isEqualTo("2026-11-01");
    CardSnapshot restored = json.treeToValue(payload, CardSnapshot.class);
    assertThat(restored.place().id()).isEqualTo(result.placeId());
    assertThat(restored.offers().getFirst().schedule().getFirst().timeUntil().toString())
        .isEqualTo("24:00");
    var folder = java.nio.file.Path.of("build/contract-samples");
    java.nio.file.Files.createDirectories(folder);
    java.nio.file.Files.writeString(
        folder.resolve("payload.json"), json.writeValueAsString(payload));
    String event =
        jdbc.getJdbcTemplate()
            .queryForObject("SELECT payload::text FROM catalog_outbox", String.class);
    java.nio.file.Files.writeString(folder.resolve("offer-published.json"), event);
    var note = new Source(SourceType.PERSONAL_VISIT, null, "Листовка");
    JsonNode noteJson = json.valueToTree(note);
    assertThat(noteJson.has("url")).isFalse();
    assertThat(noteJson.path("note").asText()).isEqualTo("Листовка");
  }

  private JsonNode get(String path, int status) throws Exception {
    try (var http = HttpClient.newHttpClient()) {
      var response =
          http.send(
              HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).GET().build(),
              HttpResponse.BodyHandlers.ofString());
      assertThat(response.statusCode()).isEqualTo(status);
      return response.body().isBlank() ? json.createObjectNode() : json.readTree(response.body());
    }
  }
}
