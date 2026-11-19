package org.studentdiscounts.catalog.publication;

public class CatalogConflict extends RuntimeException {
  public CatalogConflict(String code) {
    super(code);
  }
}
