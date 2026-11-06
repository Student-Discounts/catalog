package org.studentdiscounts.catalog.model;

import java.util.Objects;

final class Fields {
  private Fields() {}

  static String text(String value, String field, int limit) {
    Objects.requireNonNull(value, field);
    String normalized = value.strip();
    if (normalized.isEmpty() || normalized.codePointCount(0, normalized.length()) > limit) {
      throw new IllegalArgumentException(field);
    }
    return normalized;
  }

  static String optionalText(String value, String field, int limit) {
    return value == null || value.isBlank() ? null : text(value, field, limit);
  }
}
