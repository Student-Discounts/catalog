package org.studentdiscounts.catalog.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.Objects;
import java.util.UUID;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record PlaceData(
    UUID id, String name, String city, String addressLine, Location location, String categoryCode) {
  public PlaceData {
    name = Fields.text(name, "place.name", 200);
    city = Fields.text(city, "place.city", 100);
    addressLine = Fields.text(addressLine, "place.addressLine", 300);
    categoryCode = Fields.text(categoryCode, "place.categoryCode", 50);
    Objects.requireNonNull(location, "place.location");
    if (!categoryCode.matches("[a-z][a-z0-9_]*"))
      throw new IllegalArgumentException("place.categoryCode");
  }

  public PlaceData identified(UUID placeId) {
    return new PlaceData(
        Objects.requireNonNull(placeId), name, city, addressLine, location, categoryCode);
  }
}
