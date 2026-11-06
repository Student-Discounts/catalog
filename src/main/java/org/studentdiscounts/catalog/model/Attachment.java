package org.studentdiscounts.catalog.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.Objects;
import java.util.UUID;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record Attachment(UUID mediaAssetId, Purpose purpose, Integer offerIndex) {
  public enum Purpose {
    PHOTO,
    PROOF
  }

  public Attachment {
    Objects.requireNonNull(mediaAssetId, "attachment.mediaAssetId");
    Objects.requireNonNull(purpose, "attachment.purpose");
    if (offerIndex != null && offerIndex < 0)
      throw new IllegalArgumentException("attachment.offerIndex");
  }
}
