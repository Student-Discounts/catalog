package org.studentdiscounts.catalog.model;

public record Location(double lat, double lon) {
  public Location {
    if (!Double.isFinite(lat) || lat < -90 || lat > 90) {
      throw new IllegalArgumentException("location.lat");
    }
    if (!Double.isFinite(lon) || lon < -180 || lon > 180) {
      throw new IllegalArgumentException("location.lon");
    }
  }
}
