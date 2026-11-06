package org.studentdiscounts.catalog.model;

import java.time.Instant;
import java.util.Objects;

public record DiscountCard(Place place, Offer offer) {
  public DiscountCard {
    Objects.requireNonNull(place, "place");
    Objects.requireNonNull(offer, "offer");
    if (!place.id().equals(offer.placeId())) throw new IllegalArgumentException("card.placeId");
  }

  public boolean isAvailableAt(Instant moment) {
    return offer.isAvailableAt(moment.atZone(place.timezone()));
  }
}
