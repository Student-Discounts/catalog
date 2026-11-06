package org.studentdiscounts.catalog.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

public record TimeOfDay(int minutes) implements Comparable<TimeOfDay> {
  public TimeOfDay {
    if (minutes < 0 || minutes > 1440) throw new IllegalArgumentException("time");
  }

  @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
  public static TimeOfDay parse(String value) {
    if (value == null || !value.matches("(?:[01][0-9]|2[0-3]):[0-5][0-9]|24:00")) {
      throw new IllegalArgumentException("time");
    }
    return new TimeOfDay(
        Integer.parseInt(value.substring(0, 2)) * 60 + Integer.parseInt(value.substring(3, 5)));
  }

  @JsonValue
  @Override
  public String toString() {
    return "%02d:%02d".formatted(minutes / 60, minutes % 60);
  }

  @Override
  public int compareTo(TimeOfDay other) {
    return Integer.compare(minutes, other.minutes);
  }
}
