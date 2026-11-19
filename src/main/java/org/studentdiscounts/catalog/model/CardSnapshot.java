package org.studentdiscounts.catalog.model;

import java.util.List;
import java.util.Objects;

public record CardSnapshot(PlaceData place, List<OfferData> offers, List<Attachment> attachments) {
  public CardSnapshot {
    Objects.requireNonNull(place, "place");
    offers = List.copyOf(offers);
    attachments = attachments == null ? List.of() : List.copyOf(attachments);
    if (offers.isEmpty() || offers.size() > 5) throw new IllegalArgumentException("offers.count");
    if (attachments.size() > 10
        || attachments.stream().map(Attachment::mediaAssetId).distinct().count()
            != attachments.size()) {
      throw new IllegalArgumentException("attachments");
    }
    for (Attachment attachment : attachments) {
      if (attachment.offerIndex() != null && attachment.offerIndex() >= offers.size()) {
        throw new IllegalArgumentException("attachment.offerIndex");
      }
    }
    for (int i = 0; i < offers.size(); i++) {
      final int index = i;
      boolean proof =
          attachments.stream()
              .anyMatch(a ->
                  a.purpose() == Attachment.Purpose.PROOF
                      && (a.offerIndex() == null || a.offerIndex() == index));
      if (offers.get(i).source() == null && !proof) {
        throw new IllegalArgumentException("offers[" + i + "].sourceOrProof");
      }
    }
  }
}
