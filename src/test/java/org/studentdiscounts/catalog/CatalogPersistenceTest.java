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

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {"catalog.expiration.enabled=false", "catalog.bootstrap.enabled=false"})
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
                + " catalog_inbox_quarantine,catalog_submission_inbox,catalog_submission_state,catalog_submission_versions,catalog_submission_metadata,catalog_outbox,catalog_offer_snapshots,catalog_publications,offer_schedule,offers,places,categories"
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
        .isEqualTo(6);
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
    assertThat(count("catalog_outbox")).isEqualTo(2);
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
    assertThat(count("catalog_outbox")).isEqualTo(2);
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
    assertThat(count("catalog_outbox")).isEqualTo(4);
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
    assertThat(count("catalog_outbox")).isEqualTo(2);
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
    assertThat(offer.path("placeRowVersion").isInt()).isTrue();
    assertThat(offer.path("placeRowVersion").asInt()).isEqualTo(place.path("rowVersion").asInt());
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
            .queryForObject(
                "SELECT payload::text FROM catalog_outbox WHERE topic='submission.workflow.v1'",
                String.class);
    JsonNode message = json.readTree(event);
    assertThat(message.path("eventType").asText()).isEqualTo("OfferPublished");
    assertThat(message.path("schemaVersion").asInt()).isEqualTo(1);
    assertThat(message.path("producer").asText()).isEqualTo("catalog");
    assertThat(message.path("data").path("expectedRowVersion").asInt()).isEqualTo(4);
    assertThat(message.path("data").path("versionId").asText())
        .isEqualTo(command.versionId().toString());
    assertThat(message.path("data").path("offerIds").size()).isEqualTo(result.offerIds().size());
    assertThat(
            jdbc.getJdbcTemplate()
                .queryForObject(
                    "SELECT topic FROM catalog_outbox WHERE topic='submission.workflow.v1'",
                    String.class))
        .isEqualTo("submission.workflow.v1");
    assertThat(
            jdbc.getJdbcTemplate()
                .queryForObject(
                    "SELECT published_at FROM catalog_outbox WHERE topic='submission.workflow.v1'",
                    Timestamp.class))
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
            .queryForObject(
                "SELECT payload::text FROM catalog_outbox WHERE topic='submission.workflow.v1'",
                String.class);
    java.nio.file.Files.writeString(folder.resolve("offer-published.json"), event);
    var note = new Source(SourceType.PERSONAL_VISIT, null, "Листовка");
    JsonNode noteJson = json.valueToTree(note);
    assertThat(noteJson.has("url")).isFalse();
    assertThat(noteJson.path("note").asText()).isEqualTo("Листовка");
  }

  @Autowired org.studentdiscounts.catalog.events.CatalogEvents searchEvents;
  @Autowired org.studentdiscounts.catalog.events.CatalogEventBootstrap bootstrap;
  @Autowired org.studentdiscounts.catalog.lifecycle.CatalogCategories categories;
  @Autowired org.studentdiscounts.catalog.lifecycle.OfferExpiration expiration;
  @Autowired org.springframework.transaction.PlatformTransactionManager transactionManager;

  org.springframework.transaction.support.TransactionTemplate tx() {
    return new org.springframework.transaction.support.TransactionTemplate(transactionManager);
  }

  List<JsonNode> searchMessages() {
    return jdbc.getJdbcTemplate()
        .query(
            "SELECT payload::text FROM catalog_outbox WHERE topic='catalog.search.events' ORDER BY"
                + " delivery_sequence",
            (rs, n) -> json.readTree(rs.getString(1)));
  }

  @Test
  void searchSnapshotMatchesContractAndExcludesPrivateData() throws Exception {
    var command = newPlace(2);
    var result = publications.publish(command);
    var message = searchMessages().getFirst();
    assertThat(message.properties().stream().map(Map.Entry::getKey))
        .containsExactlyInAnyOrder("eventId", "eventType", "occurredAt", "placeId", "data");
    assertThat(message.path("eventType").asText()).isEqualTo("CatalogChanged");
    assertThat(message.path("placeId").asText()).isEqualTo(result.placeId().toString());
    var place = message.path("data").path("place");
    assertThat(place.path("revision").asInt()).isZero();
    assertThat(place.path("location").path("latitude").asDouble()).isEqualTo(59.9571);
    assertThat(place.path("categoryName").asText()).isEqualTo("Кафе");
    var offers = message.path("data").path("offers");
    assertThat(offers.size()).isEqualTo(2);
    assertThat(
            offers
                .get(0)
                .path("schedule")
                .get(0)
                .path("intervals")
                .get(0)
                .path("timeUntil")
                .asText())
        .isEqualTo("24:00");
    for (String field :
        List.of(
            "conditions",
            "source",
            "sourceNote",
            "attachments",
            "approvedVersionId",
            "organizationId")) {
      assertThat(message.toString()).doesNotContain("\"" + field + "\"");
    }
    assertThat(
            jdbc.queryForObject(
                "SELECT message_key FROM catalog_outbox WHERE topic='catalog.search.events'",
                Map.of(),
                UUID.class))
        .isEqualTo(result.placeId());
    java.nio.file.Files.createDirectories(java.nio.file.Path.of("build/contract-samples"));
    java.nio.file.Files.writeString(
        java.nio.file.Path.of("build/contract-samples/catalog-changed.json"),
        message.toPrettyString());
  }

  @Test
  void unchangedSnapshotsAndCategorySortDoNotProduceEvents() {
    var result = publications.publish(newPlace(1));
    tx().executeWithoutResult(ignored -> searchEvents.record(result.placeId(), result.offerIds()));
    categories.save(new Category("cafe", "Кафе", 9, true));
    assertThat(searchMessages()).hasSize(1);
    assertThat(
            jdbc.queryForObject(
                "SELECT event_revision FROM offers WHERE id=:id",
                Map.of("id", result.offerIds().getFirst()),
                Integer.class))
        .isZero();
  }

  @Test
  void categoryRenameEmitsPlaceOnlyAndKeepsOfferAndRowVersions() {
    var first = publications.publish(newPlace(1));
    var second = publications.publish(newPlace(1));
    categories.save(new Category("cafe", "Кофейни", 1, true));
    var messages = searchMessages();
    assertThat(messages).hasSize(4);
    for (var message : messages.subList(2, 4)) {
      assertThat(message.path("data").has("offers")).isFalse();
      assertThat(message.path("data").path("place").path("revision").asInt()).isEqualTo(1);
      assertThat(message.path("data").path("place").path("categoryName").asText())
          .isEqualTo("Кофейни");
    }
    assertThat(catalog.place(first.placeId()).orElseThrow().rowVersion()).isZero();
    assertThat(catalog.offer(second.offerIds().getFirst()).orElseThrow().rowVersion()).isZero();
    categories.save(new Category("cafe", "Кофейни", 1, true));
    assertThat(searchMessages()).hasSize(4);
  }

  @Test
  void changedScheduleGroupsEqualDaysAndDoesNotResendPlace() {
    var result = publications.publish(newPlace(1));
    var original = offer("Обед");
    var changed =
        new OfferData(
            result.offerIds().getFirst(),
            original.conditions(),
            original.discountType(),
            original.discountValue(),
            original.source(),
            null,
            null,
            List.of(
                new ScheduleInterval(2, TimeOfDay.parse("10:00"), TimeOfDay.parse("14:00")),
                new ScheduleInterval(1, TimeOfDay.parse("10:00"), TimeOfDay.parse("14:00"))));
    publications.publish(
        reviewed(
            ReviewedSubmission.Type.CHANGE,
            catalog.place(result.placeId()).orElseThrow().data(),
            List.of(changed),
            List.of(),
            0,
            null));
    var data = searchMessages().getLast().path("data");
    assertThat(data.has("place")).isFalse();
    assertThat(data.path("offers").get(0).path("revision").asInt()).isEqualTo(1);
    assertThat(data.path("offers").get(0).path("validFrom").isNull()).isTrue();
    assertThat(data.path("offers").get(0).path("schedule").size()).isEqualTo(1);
    assertThat(data.path("offers").get(0).path("schedule").get(0).path("daysOfWeek").toString())
        .isEqualTo("[1,2]");
  }

  @Test
  void sharedPlaceChangeAdvancesPlaceWithoutChangingSiblingOfferSnapshot() {
    var result = publications.publish(newPlace(2));
    var place = catalog.place(result.placeId()).orElseThrow();
    var changed =
        new PlaceData(
            place.id(),
            "Новое название",
            place.city(),
            place.addressLine(),
            place.location(),
            place.categoryCode());
    publications.publish(
        reviewed(
            ReviewedSubmission.Type.CHANGE,
            changed,
            List.of(catalog.offer(result.offerIds().getFirst()).orElseThrow().data()),
            List.of(),
            0,
            0));
    var data = searchMessages().getLast().path("data");
    assertThat(data.has("offers")).isFalse();
    assertThat(data.path("place").path("revision").asInt()).isEqualTo(1);
    assertThat(catalog.offer(result.offerIds().get(1)).orElseThrow().rowVersion()).isZero();
  }

  @Test
  void expirationUsesLocalEndOfDayAndIsIdempotent() {
    var result = publications.publish(newPlace(2));
    jdbc.update(
        "UPDATE offers SET valid_until='2026-11-06' WHERE place_id=:id",
        Map.of("id", result.placeId()));
    var before =
        new org.studentdiscounts.catalog.lifecycle.OfferExpiration(
            jdbc, searchEvents, Clock.fixed(Instant.parse("2026-11-06T20:59:59Z"), ZoneOffset.UTC));
    var after =
        new org.studentdiscounts.catalog.lifecycle.OfferExpiration(
            jdbc, searchEvents, Clock.fixed(Instant.parse("2026-11-06T21:00:00Z"), ZoneOffset.UTC));
    assertThat(tx().<Integer>execute(ignored -> before.expireBatch(10))).isZero();
    assertThat(tx().<Integer>execute(ignored -> after.expireBatch(10))).isEqualTo(2);
    assertThat(tx().<Integer>execute(ignored -> after.expireBatch(10))).isZero();
    var data = searchMessages().getLast().path("data");
    assertThat(data.has("place")).isFalse();
    assertThat(data.path("offers").size()).isEqualTo(2);
    assertThat(data.path("offers").get(0).path("status").asText()).isEqualTo("EXPIRED");
    assertThat(catalog.offer(result.offerIds().getFirst()).orElseThrow().rowVersion()).isEqualTo(1);
    assertThat(catalog.offer(result.offerIds().getFirst()).orElseThrow().lastVerifiedAt())
        .isEqualTo(Instant.parse("2026-11-06T10:00:00Z"));
  }

  @Test
  void concurrentExpirationOnlyTransitionsOnce() throws Exception {
    var result = publications.publish(newPlace(2));
    jdbc.update(
        "UPDATE offers SET valid_until='2026-11-05' WHERE place_id=:id",
        Map.of("id", result.placeId()));
    try (var executor = Executors.newFixedThreadPool(2)) {
      var start = new CountDownLatch(1);
      Callable<Integer> action =
          () -> {
            start.await();
            return expiration.expireBatch(10);
          };
      var first = executor.submit(action);
      var second = executor.submit(action);
      start.countDown();
      assertThat(first.get(20, TimeUnit.SECONDS) + second.get(20, TimeUnit.SECONDS)).isEqualTo(2);
    }
    assertThat(searchMessages()).hasSize(2);
  }

  @Test
  void expirationAndEventRollbackTogether() {
    var result = publications.publish(newPlace(1));
    jdbc.update(
        "UPDATE offers SET valid_until='2026-11-05' WHERE place_id=:id",
        Map.of("id", result.placeId()));
    tx().executeWithoutResult(status -> {
      expiration.expireBatch(10);
      status.setRollbackOnly();
    });
    assertThat(catalog.offer(result.offerIds().getFirst()).orElseThrow().status())
        .isEqualTo(OfferStatus.ACTIVE);
    assertThat(searchMessages()).hasSize(1);
  }

  @Test
  void bootstrapCreatesInitialEventsForPreMigrationRowsOnce() {
    var result = publications.publish(newPlace(2));
    jdbc.getJdbcTemplate().update("DELETE FROM catalog_outbox WHERE topic='catalog.search.events'");
    jdbc.getJdbcTemplate().update("UPDATE places SET event_snapshot=NULL");
    jdbc.getJdbcTemplate().update("UPDATE offers SET event_snapshot=NULL");
    assertThat(bootstrap.initializeBatch(1)).isEqualTo(1);
    assertThat(bootstrap.initializeBatch(1)).isZero();
    assertThat(searchMessages()).hasSize(1);
    assertThat(searchMessages().getFirst().path("data").path("offers").size()).isEqualTo(2);
    assertThat(searchMessages().getFirst().path("placeId").asText())
        .isEqualTo(result.placeId().toString());
  }

  @Test
  void publicCardExcludesPrivateFieldsAndUnavailableOffersBeforeWorker() throws Exception {
    var result = publications.publish(newPlace(5));
    jdbc.update(
        "UPDATE offers SET valid_until='2026-11-05' WHERE id=:id",
        Map.of("id", result.offerIds().get(1)));
    jdbc.update(
        "UPDATE offers SET valid_from='2026-11-07' WHERE id=:id",
        Map.of("id", result.offerIds().get(2)));
    jdbc.update(
        "UPDATE offers SET status='SUSPENDED' WHERE id=:id",
        Map.of("id", result.offerIds().get(3)));
    jdbc.update(
        "UPDATE offers SET status='ARCHIVED' WHERE id=:id", Map.of("id", result.offerIds().get(4)));
    var card = get("/api/v1/catalog/places/" + result.placeId(), 200);
    assertThat(card.path("offers").size()).isEqualTo(1);
    assertThat(card.path("offers").get(0).path("conditions").asText()).isEqualTo("Скидка 0");
    assertThat(card.path("offers").get(0).path("source").has("note")).isFalse();
    for (String field : List.of("attachments", "rowVersion", "approvedVersionId", "organizationId"))
      assertThat(card.toString()).doesNotContain("\"" + field + "\"");
    jdbc.update(
        "UPDATE offers SET status='EXPIRED' WHERE place_id=:id", Map.of("id", result.placeId()));
    get("/api/v1/catalog/places/" + result.placeId(), 404);
    get("/api/v1/catalog/places/not-a-uuid", 400);
    get("/api/v1/catalog/places/" + UUID.randomUUID(), 404);
  }

  @Test
  void failedDeliveryKeepsExactEventAndUnsupportedSearchPending() {
    publications.publish(newPlace(1));
    var sender =
        org.mockito.Mockito.mock(org.studentdiscounts.catalog.integration.EventTransport.class);
    org.mockito.Mockito.when(sender.topics()).thenReturn(Set.of("submission.workflow.v1"));
    var relay =
        new org.studentdiscounts.catalog.integration.CatalogOutboxRelay(
            jdbc, tx(), List.of(sender), Clock.systemUTC());
    var original =
        jdbc.getJdbcTemplate()
            .queryForObject(
                "SELECT payload::text FROM catalog_outbox WHERE topic='submission.workflow.v1'",
                String.class);
    org.mockito.Mockito.doThrow(new IllegalStateException("broker unavailable"))
        .doNothing()
        .when(sender)
        .send(
            org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.anyString());
    assertThat(relay.relayBatch(5)).isZero();
    assertThat(
            jdbc.getJdbcTemplate()
                .queryForObject(
                    "SELECT count(*) FROM catalog_outbox WHERE published_at IS NOT NULL",
                    Integer.class))
        .isZero();
    assertThat(relay.relayBatch(5)).isZero();
    var retry =
        new org.studentdiscounts.catalog.integration.CatalogOutboxRelay(
            jdbc, tx(), List.of(sender), Clock.offset(Clock.systemUTC(), Duration.ofSeconds(10)));
    assertThat(retry.relayBatch(5)).isEqualTo(1);
    assertThat(relay.relayBatch(5)).isZero();
    org.mockito.Mockito.verify(sender, org.mockito.Mockito.times(2))
        .send(
            org.mockito.ArgumentMatchers.eq("submission.workflow.v1"),
            org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.eq(original));
    assertThat(
            jdbc.getJdbcTemplate()
                .queryForObject(
                    "SELECT published_at IS NULL FROM catalog_outbox WHERE"
                        + " topic='catalog.search.events'",
                    Boolean.class))
        .isTrue();
  }

  @Test
  void concurrentRelayCannotOvertakeLockedPlaceEvent() throws Exception {
    var result = publications.publish(newPlace(1));
    categories.save(new Category("cafe", "Кофейни", 1, true));
    var entered = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var bodies = new java.util.concurrent.CopyOnWriteArrayList<String>();
    var sender =
        new org.studentdiscounts.catalog.integration.EventTransport() {
          public Set<String> topics() {
            return Set.of("catalog.search.events");
          }

          public void send(String topic, String key, String body) {
            assertThat(key).isEqualTo(result.placeId().toString());
            bodies.add(body);
            if (bodies.size() == 1) {
              entered.countDown();
              try {
                if (!release.await(10, TimeUnit.SECONDS))
                  throw new IllegalStateException("timeout");
              } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
              }
            }
          }
        };
    var relay =
        new org.studentdiscounts.catalog.integration.CatalogOutboxRelay(
            jdbc, tx(), List.of(sender), Clock.systemUTC());
    try (var executor = Executors.newFixedThreadPool(2)) {
      var first = executor.submit(() -> relay.relayBatch(1));
      try {
        assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();
        assertThat(executor.submit(() -> relay.relayBatch(1)).get(10, TimeUnit.SECONDS)).isZero();
      } finally {
        release.countDown();
      }
      assertThat(first.get(10, TimeUnit.SECONDS)).isEqualTo(1);
    }
    assertThat(relay.relayBatch(1)).isEqualTo(1);
    assertThat(json.readTree(bodies.get(0)).path("data").path("place").path("revision").asInt())
        .isZero();
    assertThat(json.readTree(bodies.get(1)).path("data").path("place").path("revision").asInt())
        .isEqualTo(1);
  }

  @Test
  void kafkaAcknowledgementAndLostDatabaseCommitReplaySameEvent() {
    var command = newPlace(1);
    var result = publications.publish(command);
    try (var kafka = new org.testcontainers.kafka.KafkaContainer("apache/kafka:4.1.0")) {
      kafka.start();
      var producerProps = new HashMap<String, Object>();
      producerProps.put("bootstrap.servers", kafka.getBootstrapServers());
      producerProps.put(
          "key.serializer", org.apache.kafka.common.serialization.StringSerializer.class);
      producerProps.put(
          "value.serializer", org.apache.kafka.common.serialization.StringSerializer.class);
      producerProps.put("acks", "all");
      producerProps.put("enable.idempotence", true);
      producerProps.put("max.block.ms", 10000);
      var factory =
          new org.springframework.kafka.core.DefaultKafkaProducerFactory<String, String>(
              producerProps);
      try {
        var template = new org.springframework.kafka.core.KafkaTemplate<>(factory);
        var transport = new org.studentdiscounts.catalog.integration.WorkflowTransport(template);
        var failOnce = new java.util.concurrent.atomic.AtomicBoolean(true);
        var losingCommit =
            new org.studentdiscounts.catalog.integration.EventTransport() {
              public Set<String> topics() {
                return transport.topics();
              }

              public void send(String topic, String key, String payload) {
                transport.send(topic, key, payload);
                if (failOnce.getAndSet(false))
                  throw new IllegalStateException(
                      "database commit lost after Kafka acknowledgement");
              }
            };
        var relay =
            new org.studentdiscounts.catalog.integration.CatalogOutboxRelay(
                jdbc, tx(), List.of(losingCommit), Clock.systemUTC());
        assertThat(relay.relayBatch(1)).isZero();
        var retry =
            new org.studentdiscounts.catalog.integration.CatalogOutboxRelay(
                jdbc,
                tx(),
                List.of(losingCommit),
                Clock.offset(Clock.systemUTC(), Duration.ofSeconds(10)));
        assertThat(retry.relayBatch(1)).isEqualTo(1);
        assertThat(relay.relayBatch(1)).isZero();
        var props = new HashMap<String, Object>();
        props.put("bootstrap.servers", kafka.getBootstrapServers());
        props.put("group.id", UUID.randomUUID().toString());
        props.put("auto.offset.reset", "earliest");
        props.put("enable.auto.commit", false);
        props.put(
            "key.deserializer", org.apache.kafka.common.serialization.StringDeserializer.class);
        props.put(
            "value.deserializer", org.apache.kafka.common.serialization.StringDeserializer.class);
        try (var consumer =
            new org.apache.kafka.clients.consumer.KafkaConsumer<String, String>(props)) {
          consumer.subscribe(List.of("submission.workflow.v1"));
          var records =
              new ArrayList<org.apache.kafka.clients.consumer.ConsumerRecord<String, String>>();
          var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
          while (records.size() < 2 && System.nanoTime() < deadline)
            consumer.poll(Duration.ofMillis(250)).forEach(records::add);
          assertThat(records).hasSize(2);
          assertThat(records.get(0).key()).isEqualTo(command.submissionId().toString());
          assertThat(records.get(0).value()).isEqualTo(records.get(1).value());
          var message = json.readTree(records.getFirst().value());
          assertThat(message.path("eventType").asText()).isEqualTo("OfferPublished");
          assertThat(message.path("data").path("expectedRowVersion").asInt()).isEqualTo(4);
          assertThat(message.path("data").path("offerIds").get(0).asText())
              .isEqualTo(result.offerIds().getFirst().toString());
        }
      } finally {
        factory.destroy();
      }
    }
  }

  @Autowired org.studentdiscounts.catalog.integration.SubmissionInbox inbox;
  @Autowired org.studentdiscounts.catalog.integration.ApprovedPublicationWorker approvedWorker;

  tools.jackson.databind.node.ObjectNode envelope(ReviewedSubmission command, String type) {
    var event =
        json.createObjectNode()
            .put("eventId", UUID.randomUUID().toString())
            .put("eventType", type)
            .put("producer", "submission")
            .put("schemaVersion", 1)
            .put("occurredAt", "2026-11-06T10:00:00Z")
            .put("correlationId", command.correlationId());
    event
        .putObject("data")
        .put("submissionId", command.submissionId().toString())
        .put("versionId", command.versionId().toString())
        .put("versionNo", command.versionNo());
    return event;
  }

  tools.jackson.databind.node.ObjectNode versionEvent(ReviewedSubmission command) {
    var event = envelope(command, "SubmissionSubmitted").put("aggregateSequence", 1);
    var data = (tools.jackson.databind.node.ObjectNode) event.path("data");
    JsonNode payload = json.valueToTree(command.payload());
    data.set("payload", payload);
    data.put("payloadSchemaVersion", 1)
        .put(
            "contentSha256",
            org.studentdiscounts.catalog.integration.SubmissionPayloadHash.hash(payload))
        .put("authorId", UUID.randomUUID().toString())
        .put("rowVersion", 1);
    data.set("baseOfferRowVersion", json.valueToTree(command.baseOfferRowVersion()));
    return event;
  }

  tools.jackson.databind.node.ObjectNode metadataEvent(ReviewedSubmission command) {
    var event = envelope(command, "SubmissionPublicationMetadata");
    var data = (tools.jackson.databind.node.ObjectNode) event.path("data");
    data.put("type", command.type().name()).put("resolvedTimezone", command.resolvedTimezone());
    data.set("basePlaceRowVersion", json.valueToTree(command.basePlaceRowVersion()));
    return event;
  }

  tools.jackson.databind.node.ObjectNode approvalEvent(ReviewedSubmission command) {
    var event = envelope(command, "SubmissionStatusChanged").put("aggregateSequence", 2);
    ((tools.jackson.databind.node.ObjectNode) event.path("data"))
        .put("fromStatus", "IN_REVIEW")
        .put("toStatus", "APPROVED")
        .put("rowVersion", command.expectedSubmissionRowVersion());
    return event;
  }

  void receive(JsonNode event) {
    String topic =
        event.path("eventType").asText().equals("SubmissionPublicationMetadata")
            ? "catalog.publication.metadata.v1"
            : "submission.events.v1";
    inbox.accept(topic, event.path("data").path("submissionId").asText(), event.toString());
  }

  @Test
  void publicationWaitsForCommittedApprovalAndExactMetadataThenReplaysSafely() {
    var command = newPlace(1);
    var version = versionEvent(command);
    var approval = approvalEvent(command);
    var metadata = metadataEvent(command);
    receive(version);
    assertThat(approvedWorker.publishBatch(10)).isZero();
    receive(approval);
    assertThat(approvedWorker.publishBatch(10)).isZero();
    receive(metadata);
    assertThat(approvedWorker.publishBatch(10)).isEqualTo(1);
    assertThat(approvedWorker.publishBatch(10)).isZero();
    receive(version);
    receive(approval);
    receive(metadata);
    assertThat(approvedWorker.publishBatch(10)).isZero();
    assertThat(count("offers")).isEqualTo(1);
    assertThat(count("catalog_submission_inbox")).isEqualTo(3);
    assertThat(count("catalog_outbox")).isEqualTo(2);
  }

  @Test
  void outOfOrderMetadataAndSnapshotDoNotLoseApproval() {
    var command = newPlace(1);
    receive(metadataEvent(command));
    receive(approvalEvent(command));
    assertThat(approvedWorker.publishBatch(10)).isZero();
    receive(versionEvent(command));
    assertThat(approvedWorker.publishBatch(10)).isEqualTo(1);
    assertThat(count("offers")).isEqualTo(1);
  }

  @Test
  void newerRejectionPreventsOlderApprovalFromPublishing() {
    var command = newPlace(1);
    receive(versionEvent(command));
    receive(metadataEvent(command));
    var rejection = approvalEvent(command).put("aggregateSequence", 3);
    ((tools.jackson.databind.node.ObjectNode) rejection.path("data")).put("toStatus", "REJECTED");
    receive(rejection);
    receive(approvalEvent(command));
    assertThat(approvedWorker.publishBatch(10)).isZero();
    assertThat(count("offers")).isZero();
  }

  @Test
  void untrustedShapeReusedIdentityAndChangedVersionAreRejected() {
    var command = newPlace(1);
    var event = versionEvent(command);
    assertThatThrownBy(() ->
            inbox.accept("submission.events.v1", UUID.randomUUID().toString(), event.toString()))
        .isInstanceOf(IllegalArgumentException.class);
    var raw =
        approvalEvent(command).put("eventType", "SubmissionApproved").put("producer", "moderation");
    assertThatThrownBy(() -> receive(raw)).isInstanceOf(IllegalArgumentException.class);
    receive(event);
    var reused = event.deepCopy().put("correlationId", "changed");
    assertThatThrownBy(() -> receive(reused)).hasMessage("EVENT_ID_REUSED");
    var changed = versionEvent(command).put("aggregateSequence", 3);
    var data = (tools.jackson.databind.node.ObjectNode) changed.path("data");
    ((tools.jackson.databind.node.ObjectNode) data.path("payload").path("place"))
        .put("name", "Подмена");
    assertThatThrownBy(() -> receive(changed)).hasMessage("VERSION_HASH_MISMATCH");
    data.put(
        "contentSha256",
        org.studentdiscounts.catalog.integration.SubmissionPayloadHash.hash(data.path("payload")));
    assertThatThrownBy(() -> receive(changed)).hasMessage("VERSION_CONTENT_CONFLICT");
    assertThat(count("catalog_submission_inbox")).isEqualTo(1);
  }

  @Test
  void staleChangeProducesConflictWithoutPartialWrites() {
    var first = publications.publish(newPlace(1));
    var place = catalog.place(first.placeId()).orElseThrow();
    var changedPlace =
        new PlaceData(
            place.id(),
            "Название не должно измениться",
            place.city(),
            place.addressLine(),
            place.location(),
            place.categoryCode());
    var command =
        reviewed(
            ReviewedSubmission.Type.CHANGE,
            changedPlace,
            List.of(offer("Устаревшая правка").identified(first.offerIds().getFirst())),
            List.of(),
            9,
            0);
    receive(versionEvent(command));
    receive(metadataEvent(command));
    receive(approvalEvent(command));
    assertThat(approvedWorker.publishBatch(10)).isEqualTo(1);
    assertThat(catalog.place(place.id()).orElseThrow()).isEqualTo(place);
    assertThat(count("catalog_publications")).isEqualTo(1);
    assertThat(searchMessages()).hasSize(1);
    var conflict =
        json.readTree(
            jdbc.getJdbcTemplate()
                .queryForObject(
                    "SELECT payload::text FROM catalog_outbox WHERE"
                        + " payload->>'eventType'='OfferPublicationConflicted'",
                    String.class));
    assertThat(conflict.path("data").path("reason").asText()).isEqualTo("CATALOG_VERSION_CONFLICT");
    assertThat(conflict.path("data").path("expectedRowVersion").asInt()).isEqualTo(4);
    assertThat(approvedWorker.publishBatch(10)).isZero();
  }

  @Test
  void missingPlaceVersionDoesNotSilentlyOverwritePlace() {
    var first = publications.publish(newPlace(1));
    var place = catalog.place(first.placeId()).orElseThrow();
    var changed =
        new PlaceData(
            place.id(),
            "Новое имя",
            place.city(),
            place.addressLine(),
            place.location(),
            place.categoryCode());
    var command =
        reviewed(
            ReviewedSubmission.Type.CHANGE,
            changed,
            List.of(offer("Правка").identified(first.offerIds().getFirst())),
            List.of(),
            0,
            null);
    receive(versionEvent(command));
    receive(metadataEvent(command));
    receive(approvalEvent(command));
    assertThat(approvedWorker.publishBatch(10)).isEqualTo(1);
    assertThat(catalog.place(place.id()).orElseThrow()).isEqualTo(place);
    assertThat(
            jdbc.getJdbcTemplate()
                .queryForObject(
                    "SELECT publication_outcome FROM catalog_submission_state", String.class))
        .isEqualTo("CONFLICTED");
  }

  @Test
  void upgradesVersionTwoDatabaseWithoutLosingPublishedData() throws Exception {
    String database = "catalog_upgrade_" + UUID.randomUUID().toString().replace("-", "");
    jdbc.getJdbcTemplate().execute("CREATE DATABASE " + database);
    String url =
        "jdbc:postgresql://"
            + DATABASE.getHost()
            + ":"
            + DATABASE.getMappedPort(5432)
            + "/"
            + database;
    try {
      var source =
          new org.springframework.jdbc.datasource.DriverManagerDataSource(
              url, DATABASE.getUsername(), DATABASE.getPassword());
      var oldFlyway = org.flywaydb.core.Flyway.configure().dataSource(source).target("2").load();
      oldFlyway.migrate();
      var old = new org.springframework.jdbc.core.JdbcTemplate(source);
      UUID place = UUID.randomUUID(), offer = UUID.randomUUID(), version = UUID.randomUUID();
      old.update("INSERT INTO categories(code,name) VALUES('cafe','Кафе')");
      old.update(
          "INSERT INTO"
              + " places(id,name,city,address_line,address_normalized,location,category_code,timezone)"
              + " VALUES(?,'Кафе','Москва','ул. Ленина, 1','ул. ленина,"
              + " 1',ST_SetSRID(ST_MakePoint(37.6,55.7),4326)::geography,'cafe','Europe/Moscow')",
          place);
      old.update(
          "INSERT INTO"
              + " offers(id,place_id,status,conditions,discount_type,source_type,source_url,last_verified_at,approved_version_id)"
              + " VALUES(?,?,'ACTIVE','Скидка"
              + " студентам','OTHER','WEBSITE','https://example.com',now(),?)",
          offer,
          place,
          version);
      old.update(
          "INSERT INTO catalog_publications(version_id,submission_id,version_no,command_hash)"
              + " VALUES(?,?,1,?)",
          version,
          UUID.randomUUID(),
          "0".repeat(64));
      old.update(
          "INSERT INTO catalog_outbox(id,version_id,topic,message_key,payload,occurred_at)"
              + " VALUES(?,?,'submission.workflow.v1',?,'{}',now())",
          UUID.randomUUID(),
          version,
          UUID.randomUUID());
      org.flywaydb.core.Flyway.configure().dataSource(source).load().migrate();
      assertThat(old.queryForObject("SELECT count(*) FROM offers WHERE id=?", Integer.class, offer))
          .isEqualTo(1);
      assertThat(
              old.queryForObject("SELECT conditions FROM offers WHERE id=?", String.class, offer))
          .isEqualTo("Скидка студентам");
      assertThat(
              old.queryForObject(
                  "SELECT event_revision FROM places WHERE id=?", Integer.class, place))
          .isZero();
      assertThat(
              old.queryForObject(
                  "SELECT event_snapshot IS NULL FROM places WHERE id=?", Boolean.class, place))
          .isTrue();
      assertThat(old.queryForObject("SELECT delivery_sequence FROM catalog_outbox", Long.class))
          .isPositive();
      assertThat(
              old.queryForObject(
                  "SELECT count(*) FROM flyway_schema_history WHERE success", Integer.class))
          .isEqualTo(6);
    } finally {
      jdbc.getJdbcTemplate().execute("DROP DATABASE " + database + " WITH (FORCE)");
    }
  }

  @Test
  void unavailableSearchDoesNotBlockWorkflowStream() {
    publications.publish(newPlace(1));
    var search =
        org.mockito.Mockito.mock(org.studentdiscounts.catalog.integration.EventTransport.class);
    var workflow =
        org.mockito.Mockito.mock(org.studentdiscounts.catalog.integration.EventTransport.class);
    org.mockito.Mockito.when(search.topics()).thenReturn(Set.of("catalog.search.events"));
    org.mockito.Mockito.when(workflow.topics()).thenReturn(Set.of("submission.workflow.v1"));
    org.mockito.Mockito.doThrow(new IllegalStateException("registry offline"))
        .when(search)
        .send(
            org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.anyString());
    var relay =
        new org.studentdiscounts.catalog.integration.CatalogOutboxRelay(
            jdbc, tx(), List.of(search, workflow), Clock.systemUTC());
    assertThat(relay.relayBatch(10)).isEqualTo(1);
    assertThat(
            jdbc.getJdbcTemplate()
                .queryForObject(
                    "SELECT delivery_failures FROM catalog_outbox WHERE"
                        + " topic='catalog.search.events'",
                    Integer.class))
        .isEqualTo(1);
    assertThat(
            jdbc.getJdbcTemplate()
                .queryForObject(
                    "SELECT published_at IS NULL FROM catalog_outbox WHERE"
                        + " topic='catalog.search.events'",
                    Boolean.class))
        .isTrue();
  }

  @Test
  void proofAllowsPartialSourceAndMalformedSnapshotDoesNotStarveFollowingApproval() {
    var partial = newPlace(1);
    var version = versionEvent(partial);
    var payload = (tools.jackson.databind.node.ObjectNode) version.path("data").path("payload");
    ((tools.jackson.databind.node.ObjectNode) payload.path("offers").get(0))
        .putObject("source")
        .put("type", "WEBSITE");
    payload
        .putArray("attachments")
        .addObject()
        .put("mediaAssetId", UUID.randomUUID().toString())
        .put("purpose", "PROOF")
        .put("offerIndex", 0);
    ((tools.jackson.databind.node.ObjectNode) version.path("data"))
        .put(
            "contentSha256",
            org.studentdiscounts.catalog.integration.SubmissionPayloadHash.hash(payload));
    receive(version);
    receive(metadataEvent(partial));
    receive(approvalEvent(partial));
    assertThat(approvedWorker.publishBatch(10)).isEqualTo(1);
    assertThat(count("offers")).isEqualTo(1);
    assertThat(
            jdbc.getJdbcTemplate()
                .queryForObject("SELECT source_type IS NULL FROM offers", Boolean.class))
        .isTrue();
    var invalid = newPlace(1);
    var invalidVersion = versionEvent(invalid);
    ((tools.jackson.databind.node.ObjectNode)
            invalidVersion.path("data").path("payload").path("place"))
        .put("name", "");
    ((tools.jackson.databind.node.ObjectNode) invalidVersion.path("data"))
        .put(
            "contentSha256",
            org.studentdiscounts.catalog.integration.SubmissionPayloadHash.hash(
                invalidVersion.path("data").path("payload")));
    receive(invalidVersion);
    receive(metadataEvent(invalid));
    receive(approvalEvent(invalid));
    assertThat(approvedWorker.publishBatch(10)).isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "SELECT publication_outcome FROM catalog_submission_state WHERE submission_id=:id",
                Map.of("id", invalid.submissionId()),
                String.class))
        .isEqualTo("BLOCKED");
    var valid = newPlace(1);
    receive(versionEvent(valid));
    receive(metadataEvent(valid));
    receive(approvalEvent(valid));
    assertThat(approvedWorker.publishBatch(10)).isEqualTo(1);
    assertThat(count("offers")).isEqualTo(2);
  }

  @Test
  void invalidRecordIsQuarantinedBeforeNextValidRecord() {
    var quarantine = new org.studentdiscounts.catalog.integration.InboxQuarantine(jdbc);
    var listener =
        new org.studentdiscounts.catalog.integration.SubmissionListener(inbox, quarantine);
    var invalid =
        new org.apache.kafka.clients.consumer.ConsumerRecord<String, String>(
            "catalog.publication.metadata.v1", 0, 1, "bad-key", "{invalid");
    listener.receive(invalid);
    assertThat(count("catalog_inbox_quarantine")).isEqualTo(1);
    var command = newPlace(1);
    listener.receive(
        new org.apache.kafka.clients.consumer.ConsumerRecord<>(
            "catalog.publication.metadata.v1",
            0,
            2,
            command.submissionId().toString(),
            metadataEvent(command).toString()));
    assertThat(count("catalog_submission_metadata")).isEqualTo(1);
    receive(versionEvent(command));
    receive(approvalEvent(command));
    assertThat(approvedWorker.publishBatch(10)).isEqualTo(1);
  }

  @Test
  void slowFailingStreamsCannotStarveLaterWorkflowAcrossBatches() {
    for (String topic :
        List.of("catalog.search.events", "catalog.search.events", "submission.workflow.v1"))
      jdbc.update(
          "INSERT INTO catalog_outbox(id,topic,message_key,payload,occurred_at)"
              + " VALUES(:id,:topic,:key,'{}',now())",
          Map.of("id", UUID.randomUUID(), "topic", topic, "key", UUID.randomUUID()));
    var now =
        new java.util.concurrent.atomic.AtomicReference<>(Instant.parse("2026-11-19T12:00:00Z"));
    var clock =
        new Clock() {
          public ZoneId getZone() {
            return ZoneOffset.UTC;
          }

          public Clock withZone(ZoneId zone) {
            return this;
          }

          public Instant instant() {
            return now.get();
          }
        };
    var deliveries = new java.util.concurrent.atomic.AtomicInteger();
    var sender =
        new org.studentdiscounts.catalog.integration.EventTransport() {
          public Set<String> topics() {
            return Set.of("catalog.search.events", "submission.workflow.v1");
          }

          public void send(String topic, String key, String payload) {
            if (topic.equals("catalog.search.events")) {
              now.updateAndGet(t -> t.plusSeconds(6));
              throw new IllegalStateException("slow registry failure");
            }
            deliveries.incrementAndGet();
          }
        };
    var relay =
        new org.studentdiscounts.catalog.integration.CatalogOutboxRelay(
            jdbc, tx(), List.of(sender), clock);
    assertThat(relay.relayBatch(1)).isZero();
    assertThat(relay.relayBatch(1)).isZero();
    assertThat(relay.relayBatch(1)).isEqualTo(1);
    assertThat(deliveries.get()).isEqualTo(1);
  }

  private JsonNode get(String path, int status) throws Exception {
    return send(
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).GET(), status);
  }

  private JsonNode send(HttpRequest.Builder request, int status) throws Exception {
    try (var http = HttpClient.newHttpClient()) {
      var response = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
      assertThat(response.statusCode()).isEqualTo(status);
      JsonNode body =
          response.body().isBlank() ? json.createObjectNode() : json.readTree(response.body());
      if (status >= 400) {
        assertThat(response.headers().firstValue("Content-Type").orElse(""))
            .startsWith("application/problem+json");
        assertThat(body.path("status").asInt()).isEqualTo(status);
        assertThat(body.path("type").asText()).isEqualTo("about:blank");
        assertThat(body.path("code").asText()).isNotBlank();
      }
      return body;
    }
  }

  @Test
  void errorsUseProblemJson() throws Exception {
    assertThat(get("/api/v1/catalog/unknown", 404).path("code").asText()).isEqualTo("NOT_FOUND");
    assertThat(get("/unknown-root", 404).path("code").asText()).isEqualTo("NOT_FOUND");
    assertThat(get("/api/v1/catalog/places/not-a-uuid", 400).path("code").asText())
        .isEqualTo("MALFORMED_REQUEST");
    var deleted =
        HttpRequest.newBuilder(
                URI.create(
                    "http://127.0.0.1:" + port + "/api/v1/catalog/places/" + UUID.randomUUID()))
            .DELETE();
    assertThat(send(deleted, 405).path("code").asText()).isEqualTo("METHOD_NOT_ALLOWED");
    var correlated =
        HttpRequest.newBuilder(
                URI.create(
                    "http://127.0.0.1:" + port + "/api/v1/catalog/places/" + UUID.randomUUID()))
            .header("X-Correlation-ID", "gateway-test-1")
            .GET();
    assertThat(send(correlated, 404).path("correlationId").asText()).isEqualTo("gateway-test-1");
    var forged =
        HttpRequest.newBuilder(
                URI.create(
                    "http://127.0.0.1:" + port + "/api/v1/catalog/places/" + UUID.randomUUID()))
            .header("X-Correlation-ID", "bad value with spaces")
            .GET();
    assertThat(send(forged, 404).has("correlationId")).isFalse();
  }
}
