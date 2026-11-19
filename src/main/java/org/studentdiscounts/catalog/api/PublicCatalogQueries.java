package org.studentdiscounts.catalog.api;

import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import org.studentdiscounts.catalog.model.*;
import org.studentdiscounts.catalog.persistence.CatalogRepository;

@Service
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
public class PublicCatalogQueries {
  private final CatalogRepository repository;
  private final NamedParameterJdbcTemplate jdbc;
  private final Clock clock;

  public PublicCatalogQueries(
      CatalogRepository repository, NamedParameterJdbcTemplate jdbc, Clock clock) {
    this.repository = repository;
    this.jdbc = jdbc;
    this.clock = clock;
  }

  public PlaceCard place(UUID id) {
    var place = repository.place(id).orElseThrow(PublicCatalogQueries::notFound);
    var now = clock.instant();
    var date = now.atZone(place.timezone()).toLocalDate();
    var ids =
        jdbc.query(
            """
            SELECT id FROM offers WHERE place_id=:id AND status='ACTIVE'
            AND (valid_from IS NULL OR valid_from<=:date) AND (valid_until IS NULL OR valid_until>=:date)
            ORDER BY last_verified_at DESC,id
            """,
            Map.of("id", id, "date", date),
            (rs, n) -> rs.getObject(1, UUID.class));
    if (ids.isEmpty()) throw notFound();
    var offers =
        ids.stream()
            .map(offerId -> {
              var offer = repository.offer(offerId).orElseThrow();
              var data = offer.data();
              var source =
                  data.source() == null
                      ? null
                      : new PublicSource(data.source().type(), data.source().url());
              return new OfferCard(
                  offer.id(),
                  data.conditions(),
                  data.discountType(),
                  data.discountValue(),
                  source,
                  offer.lastVerifiedAt(),
                  data.validFrom(),
                  data.validUntil(),
                  data.schedule());
            })
            .toList();
    var category = repository.category(place.categoryCode()).orElseThrow();
    return new PlaceCard(
        place.id(),
        place.name(),
        place.city(),
        place.addressLine(),
        place.location(),
        new PublicCategory(category.code(), category.name()),
        place.timezone().getId(),
        offers);
  }

  private static ResponseStatusException notFound() {
    return new ResponseStatusException(HttpStatus.NOT_FOUND);
  }

  public record PublicCategory(String code, String name) {}

  public record PublicSource(SourceType type, String url) {}

  public record OfferCard(
      UUID id,
      String conditions,
      DiscountType discountType,
      BigDecimal discountValue,
      PublicSource source,
      Instant lastVerifiedAt,
      LocalDate validFrom,
      LocalDate validUntil,
      List<ScheduleInterval> schedule) {}

  public record PlaceCard(
      UUID id,
      String name,
      String city,
      String addressLine,
      Location location,
      PublicCategory category,
      String timezone,
      List<OfferCard> offers) {}
}
