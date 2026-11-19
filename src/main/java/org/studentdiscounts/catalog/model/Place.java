package org.studentdiscounts.catalog.model;

import java.time.Instant;
import java.time.ZoneId;
import java.util.UUID;

public record Place(
    UUID id,
    String name,
    String city,
    String addressLine,
    String addressNormalized,
    Location location,
    String categoryCode,
    ZoneId timezone,
    UUID organizationId,
    int rowVersion,
    Instant createdAt,
    Instant updatedAt) {
  public Place {
    java.util.Objects.requireNonNull(id, "place.id");
    var data = new PlaceData(id, name, city, addressLine, location, categoryCode);
    name = data.name();
    city = data.city();
    addressLine = data.addressLine();
    categoryCode = data.categoryCode();
    addressNormalized = Fields.text(addressNormalized, "place.addressNormalized", 300);
    java.util.Objects.requireNonNull(timezone, "place.timezone");
    java.util.Objects.requireNonNull(createdAt, "place.createdAt");
    java.util.Objects.requireNonNull(updatedAt, "place.updatedAt");
    if (rowVersion < 0) throw new IllegalArgumentException("place.rowVersion");
  }

  public PlaceData data() {
    return new PlaceData(id, name, city, addressLine, location, categoryCode);
  }
}
