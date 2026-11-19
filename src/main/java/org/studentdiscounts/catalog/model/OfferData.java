package org.studentdiscounts.catalog.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record OfferData(
    UUID id,
    String conditions,
    DiscountType discountType,
    BigDecimal discountValue,
    Source source,
    LocalDate validFrom,
    LocalDate validUntil,
    List<ScheduleInterval> schedule) {
  public OfferData {
    conditions = Fields.text(conditions, "offer.conditions", 2000);
    discountType = discountType == null ? DiscountType.OTHER : discountType;
    if (discountValue != null) {
      discountValue = discountValue.stripTrailingZeros();
      if (discountValue.signum() <= 0
          || discountValue.scale() > 2
          || discountValue.compareTo(new BigDecimal("99999999.99")) > 0
          || discountType == DiscountType.PERCENT
              && (discountValue.compareTo(BigDecimal.ONE) < 0
                  || discountValue.compareTo(BigDecimal.valueOf(100)) > 0)
          || discountType == DiscountType.FREE_ITEM
          || discountType == DiscountType.OTHER) {
        throw new IllegalArgumentException("offer.discountValue");
      }
    }
    if (validFrom != null && validUntil != null && validUntil.isBefore(validFrom)) {
      throw new IllegalArgumentException("offer.validUntil");
    }
    schedule = schedule == null ? List.of() : List.copyOf(schedule);
    if (schedule.size() > 28) throw new IllegalArgumentException("offer.schedule");
    for (int i = 0; i < schedule.size(); i++) {
      for (int j = i + 1; j < schedule.size(); j++) {
        if (schedule.get(i).overlaps(schedule.get(j))) {
          throw new IllegalArgumentException("offer.schedule.overlap");
        }
      }
    }
  }

  public OfferData identified(UUID offerId) {
    return new OfferData(
        offerId, conditions, discountType, discountValue, source, validFrom, validUntil, schedule);
  }
}
