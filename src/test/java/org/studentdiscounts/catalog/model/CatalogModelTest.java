package org.studentdiscounts.catalog.model;

import static org.assertj.core.api.Assertions.*;

import java.math.BigDecimal;
import java.time.*;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.studentdiscounts.catalog.persistence.CatalogIds;

class CatalogModelTest {
  private final PlaceData place =
      new PlaceData(
          null, " Кофейня «Зерно» ", "Москва", "ул. Лесная, 1", new Location(55.75, 37.61), "cafe");
  private final Source website =
      new Source(SourceType.WEBSITE, "https://example.com/students", null);

  private OfferData offer(Source source, List<ScheduleInterval> schedule) {
    return new OfferData(
        null,
        "15% по студенческому",
        DiscountType.PERCENT,
        new BigDecimal("15"),
        source,
        LocalDate.of(2026, 11, 1),
        LocalDate.of(2026, 11, 30),
        schedule);
  }

  @Test
  void generatesUuidV7WithoutRestrictingExternalUuidVersions() {
    var ids = java.util.stream.IntStream.range(0, 1000).mapToObj(i -> CatalogIds.newId()).toList();
    assertThat(ids).doesNotHaveDuplicates().allSatisfy(id -> assertThat(id.version()).isEqualTo(7));
    assertThat(new Attachment(UUID.randomUUID(), Attachment.Purpose.PROOF, null)).isNotNull();
  }

  @Test
  void coordinatesRejectNaNInfinityAndOutOfBounds() {
    for (double lat : new double[] {Double.NaN, Double.POSITIVE_INFINITY, -90.1, 90.1}) {
      assertThatIllegalArgumentException().isThrownBy(() -> new Location(lat, 0));
    }
    assertThatIllegalArgumentException().isThrownBy(() -> new Location(0, 180.1));
    assertThat(new Location(-90, 180)).isNotNull();
  }

  @Test
  void supportsMidnightWithoutLosingLastMinute() {
    var friday = new ScheduleInterval(5, TimeOfDay.parse("22:00"), TimeOfDay.parse("24:00"));
    var saturday = new ScheduleInterval(6, TimeOfDay.parse("00:00"), TimeOfDay.parse("02:00"));
    assertThat(offer(website, List.of(friday, saturday)).schedule()).hasSize(2);
    assertThat(friday.timeUntil().toString()).isEqualTo("24:00");
    assertThatIllegalArgumentException().isThrownBy(() -> TimeOfDay.parse("24:01"));
    assertThatIllegalArgumentException()
        .isThrownBy(
            () -> new ScheduleInterval(5, TimeOfDay.parse("22:00"), TimeOfDay.parse("02:00")));
  }

  @Test
  void adjacentIntervalsAreAllowedAndOverlapsRejected() {
    var a = new ScheduleInterval(1, TimeOfDay.parse("10:00"), TimeOfDay.parse("14:00"));
    var b = new ScheduleInterval(1, TimeOfDay.parse("14:00"), TimeOfDay.parse("18:00"));
    var overlap = new ScheduleInterval(1, TimeOfDay.parse("13:59"), TimeOfDay.parse("15:00"));
    assertThat(offer(website, List.of(a, b)).schedule()).hasSize(2);
    assertThatIllegalArgumentException().isThrownBy(() -> offer(website, List.of(a, overlap)));
    assertThatIllegalArgumentException()
        .isThrownBy(() -> new ScheduleInterval(8, a.timeFrom(), a.timeUntil()));
  }

  @Test
  void proofMustCoverTheOfferThatHasNoSource() {
    var proof = new Attachment(UUID.randomUUID(), Attachment.Purpose.PROOF, 0);
    assertThatIllegalArgumentException()
        .isThrownBy(() ->
            new CardSnapshot(
                place, List.of(offer(null, List.of()), offer(null, List.of())), List.of(proof)));
    var common = new Attachment(proof.mediaAssetId(), proof.purpose(), null);
    assertThat(
            new CardSnapshot(
                    place, List.of(offer(null, List.of()), offer(null, List.of())), List.of(common))
                .offers())
        .hasSize(2);
    assertThatIllegalArgumentException()
        .isThrownBy(() ->
            new CardSnapshot(
                place,
                List.of(offer(website, List.of())),
                List.of(new Attachment(proof.mediaAssetId(), proof.purpose(), 1))));
  }

  @Test
  void snapshotsDefensivelyCopyCollections() {
    List<OfferData> offers = new ArrayList<>(List.of(offer(website, List.of())));
    CardSnapshot snapshot = new CardSnapshot(place, offers, List.of());
    offers.clear();
    assertThat(snapshot.offers()).hasSize(1);
    assertThatExceptionOfType(UnsupportedOperationException.class)
        .isThrownBy(() -> snapshot.offers().clear());
    assertThat(snapshot.place().name()).isEqualTo("Кофейня «Зерно»");
  }

  @Test
  void discountsRejectInvalidRangesAndSilentRounding() {
    for (String value : List.of("0", "0.5", "-1", "100.01", "15.001")) {
      assertThatIllegalArgumentException()
          .isThrownBy(() ->
              new OfferData(
                  null,
                  "Скидка",
                  DiscountType.PERCENT,
                  new BigDecimal(value),
                  website,
                  null,
                  null,
                  List.of()));
    }
    assertThatIllegalArgumentException()
        .isThrownBy(() ->
            new OfferData(
                null,
                "Подарок",
                DiscountType.FREE_ITEM,
                BigDecimal.ONE,
                website,
                null,
                null,
                List.of()));
  }

  @Test
  void rejectsBlankFieldsInvalidSourcesAndBackwardsValidity() {
    assertThatIllegalArgumentException().isThrownBy(() -> new Source(SourceType.OTHER, null, " "));
    assertThatIllegalArgumentException()
        .isThrownBy(() -> new Source(SourceType.WEBSITE, "javascript:alert(1)", null));
    assertThatIllegalArgumentException()
        .isThrownBy(() -> new Source(SourceType.WEBSITE, "https://user:pass@example.com", null));
    assertThatIllegalArgumentException().isThrownBy(() -> new Category("cafe", " ", 0, true));
    assertThatIllegalArgumentException()
        .isThrownBy(() ->
            new OfferData(
                null,
                "Скидка",
                null,
                null,
                website,
                LocalDate.of(2026, 11, 30),
                LocalDate.of(2026, 11, 1),
                List.of()));
  }

  @Test
  void availabilityUsesPlaceTimezoneAndHalfOpenIntervals() {
    Instant created = Instant.parse("2026-11-01T00:00:00Z");
    UUID placeId = CatalogIds.newId();
    UUID offerId = CatalogIds.newId();
    Place persisted =
        new Place(
            placeId,
            place.name(),
            place.city(),
            place.addressLine(),
            "лесная, 1",
            place.location(),
            place.categoryCode(),
            ZoneId.of("Asia/Novosibirsk"),
            null,
            0,
            created,
            created);
    var hours =
        List.of(new ScheduleInterval(5, TimeOfDay.parse("22:00"), TimeOfDay.parse("24:00")));
    var data = offer(website, hours).identified(offerId);
    var offer =
        new Offer(
            offerId,
            placeId,
            OfferStatus.ACTIVE,
            data,
            CatalogIds.newId(),
            created,
            0,
            created,
            created);
    var card = new DiscountCard(persisted, offer);
    assertThat(card.isAvailableAt(Instant.parse("2026-11-06T15:00:00Z"))).isTrue();
    assertThat(card.isAvailableAt(Instant.parse("2026-11-06T16:59:59Z"))).isTrue();
    assertThat(card.isAvailableAt(Instant.parse("2026-11-06T17:00:00Z"))).isFalse();
    assertThat(card.isAvailableAt(Instant.parse("2026-12-04T16:00:00Z"))).isFalse();
  }
}
