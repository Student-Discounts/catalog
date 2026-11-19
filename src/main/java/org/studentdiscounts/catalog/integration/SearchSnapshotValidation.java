package org.studentdiscounts.catalog.integration;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashSet;
import org.studentdiscounts.catalog.model.TimeOfDay;

final class SearchSnapshotValidation {
  private SearchSnapshotValidation() {}

  static void validate(JsonNode message) {
    var data = message.path("data");
    if (data.has("place")) {
      String zone = data.path("place").path("timezone").asText();
      if (!ZoneId.getAvailableZoneIds().contains(zone)
          || (!zone.equals("UTC") && !zone.contains("/")))
        throw new IllegalArgumentException("IANA timezone");
    }
    var ids = new HashSet<java.util.UUID>();
    for (var offer : data.path("offers")) {
      if (!ids.add(java.util.UUID.fromString(offer.path("offerId").asText())))
        throw new IllegalArgumentException("duplicate offer");
      if (!offer.path("validFrom").isNull() && !offer.path("validUntil").isNull()) {
        if (LocalDate.parse(offer.path("validUntil").asText())
            .isBefore(LocalDate.parse(offer.path("validFrom").asText())))
          throw new IllegalArgumentException("date range");
      }
      int expanded = 0;
      var days = new HashSet<Integer>();
      for (var group : offer.path("schedule")) {
        int count = group.path("daysOfWeek").size();
        for (var day : group.path("daysOfWeek"))
          if (!days.add(day.asInt())) throw new IllegalArgumentException("duplicate schedule day");
        int previousEnd = -1;
        for (var interval : group.path("intervals")) {
          int start = TimeOfDay.parse(interval.path("timeFrom").asText()).minutes();
          int end = TimeOfDay.parse(interval.path("timeUntil").asText()).minutes();
          if (start >= end || start < previousEnd)
            throw new IllegalArgumentException("schedule overlap or order");
          previousEnd = end;
          expanded += count;
          if (expanded > 28) throw new IllegalArgumentException("expanded schedule limit");
        }
      }
    }
  }
}
