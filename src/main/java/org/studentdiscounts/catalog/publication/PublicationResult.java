package org.studentdiscounts.catalog.publication;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record PublicationResult(UUID placeId, List<UUID> offerIds, Instant publishedAt) {
  public PublicationResult {
    offerIds = List.copyOf(offerIds);
  }
}
