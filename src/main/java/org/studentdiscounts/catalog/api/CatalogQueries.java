package org.studentdiscounts.catalog.api;

import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import org.studentdiscounts.catalog.model.Category;
import org.studentdiscounts.catalog.model.Location;
import org.studentdiscounts.catalog.model.OfferStatus;
import org.studentdiscounts.catalog.persistence.CatalogRepository;
import tools.jackson.databind.JsonNode;

@Service
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
public class CatalogQueries {
  public record PlaceView(
      UUID id,
      String name,
      String city,
      String addressLine,
      Location location,
      String categoryCode,
      String timezone,
      int rowVersion) {}

  public record OfferView(
      UUID id, UUID placeId, OfferStatus status, int rowVersion, JsonNode payload) {}

  private final CatalogRepository repository;

  public CatalogQueries(CatalogRepository repository) {
    this.repository = repository;
  }

  public Category category(String code) {
    return repository.category(code).orElseThrow(CatalogQueries::notFound);
  }

  public PlaceView place(UUID id) {
    var place = repository.place(id).orElseThrow(CatalogQueries::notFound);
    return new PlaceView(
        place.id(),
        place.name(),
        place.city(),
        place.addressLine(),
        place.location(),
        place.categoryCode(),
        place.timezone().getId(),
        place.rowVersion());
  }

  public OfferView offer(UUID id) {
    var offer = repository.offer(id).orElseThrow(CatalogQueries::notFound);
    return new OfferView(
        offer.id(), offer.placeId(), offer.status(), offer.rowVersion(), repository.payload(id));
  }

  private static ResponseStatusException notFound() {
    return new ResponseStatusException(HttpStatus.NOT_FOUND);
  }
}
