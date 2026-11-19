package org.studentdiscounts.catalog.model;

public record Category(String code, String name, int sortOrder, boolean isActive) {
  public Category {
    code = Fields.text(code, "category.code", 50);
    name = Fields.text(name, "category.name", 100);
    if (!code.matches("[a-z][a-z0-9_]*")) throw new IllegalArgumentException("category.code");
  }
}
