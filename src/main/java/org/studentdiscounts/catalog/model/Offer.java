package org.studentdiscounts.catalog.model;

import java.time.Instant;
import java.time.ZonedDateTime;
import java.util.UUID;

public record Offer(
    UUID id,
    UUID placeId,
    OfferStatus status,
    OfferData data,
    UUID approvedVersionId,
    Instant lastVerifiedAt,
    int rowVersion,
    Instant createdAt,
    Instant updatedAt) {
  public Offer {
    java.util.Objects.requireNonNull(id, "offer.id");
    java.util.Objects.requireNonNull(placeId, "offer.placeId");
    java.util.Objects.requireNonNull(status, "offer.status");
    java.util.Objects.requireNonNull(data, "offer.data");
    java.util.Objects.requireNonNull(approvedVersionId, "offer.approvedVersionId");
    java.util.Objects.requireNonNull(lastVerifiedAt, "offer.lastVerifiedAt");
    java.util.Objects.requireNonNull(createdAt, "offer.createdAt");
    java.util.Objects.requireNonNull(updatedAt, "offer.updatedAt");
    if (!id.equals(data.id()) || rowVersion < 0)
      throw new IllegalArgumentException("offer.identity");
  }

  public boolean isAvailableAt(ZonedDateTime localTime) {
    if (status != OfferStatus.ACTIVE
        || data.validFrom() != null && localTime.toLocalDate().isBefore(data.validFrom())
        || data.validUntil() != null && localTime.toLocalDate().isAfter(data.validUntil()))
      return false;
    int minute = localTime.getHour() * 60 + localTime.getMinute();
    return data.schedule().isEmpty()
        || data.schedule().stream()
            .anyMatch(interval ->
                interval.dayOfWeek() == localTime.getDayOfWeek().getValue()
                    && minute >= interval.timeFrom().minutes()
                    && minute < interval.timeUntil().minutes());
  }
}
