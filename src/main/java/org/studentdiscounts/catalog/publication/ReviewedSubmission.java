package org.studentdiscounts.catalog.publication;

import java.time.ZoneId;
import java.util.Objects;
import java.util.UUID;
import org.studentdiscounts.catalog.model.CardSnapshot;

public record ReviewedSubmission(
    UUID submissionId,
    UUID versionId,
    int versionNo,
    int expectedSubmissionRowVersion,
    Type type,
    CardSnapshot payload,
    String resolvedTimezone,
    Integer baseOfferRowVersion,
    Integer basePlaceRowVersion,
    String correlationId) {
  public enum Type {
    NEW_PLACE,
    NEW_OFFER,
    CHANGE
  }

  public ReviewedSubmission {
    Objects.requireNonNull(submissionId, "submissionId");
    Objects.requireNonNull(versionId, "versionId");
    Objects.requireNonNull(type, "type");
    Objects.requireNonNull(payload, "payload");
    ZoneId.of(resolvedTimezone);
    if (!resolvedTimezone.equals("UTC") && !resolvedTimezone.contains("/")) {
      throw new IllegalArgumentException("resolvedTimezone");
    }
    if (versionNo < 1
        || expectedSubmissionRowVersion < 0
        || baseOfferRowVersion != null && baseOfferRowVersion < 0
        || basePlaceRowVersion != null && basePlaceRowVersion < 0
        || correlationId == null
        || !correlationId.matches("[A-Za-z0-9._:-]{1,64}")) {
      throw new IllegalArgumentException("reviewedSubmission");
    }
    boolean newPlace = type == Type.NEW_PLACE;
    boolean change = type == Type.CHANGE;
    if (newPlace != (payload.place().id() == null)
        || change && (payload.offers().size() != 1 || baseOfferRowVersion == null)
        || !change && (baseOfferRowVersion != null || basePlaceRowVersion != null)
        || payload.offers().stream().anyMatch(o -> change != (o.id() != null))) {
      throw new IllegalArgumentException("reviewedSubmission.targets");
    }
  }
}
