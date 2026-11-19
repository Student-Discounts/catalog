package org.studentdiscounts.catalog.model;

import java.util.Objects;

public record ScheduleInterval(int dayOfWeek, TimeOfDay timeFrom, TimeOfDay timeUntil) {
  public ScheduleInterval {
    Objects.requireNonNull(timeFrom, "schedule.timeFrom");
    Objects.requireNonNull(timeUntil, "schedule.timeUntil");
    if (dayOfWeek < 1 || dayOfWeek > 7 || timeFrom.compareTo(timeUntil) >= 0) {
      throw new IllegalArgumentException("schedule.interval");
    }
  }

  public boolean overlaps(ScheduleInterval other) {
    return dayOfWeek == other.dayOfWeek
        && timeFrom.compareTo(other.timeUntil) < 0
        && other.timeFrom.compareTo(timeUntil) < 0;
  }
}
