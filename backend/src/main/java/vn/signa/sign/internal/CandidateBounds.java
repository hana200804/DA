package vn.signa.sign.internal;

import java.util.List;
import vn.signa.sign.clustering.Geo;
import vn.signa.sign.clustering.GeoPoint;

record CandidateBounds(double south, double north, List<Box> boxes) {

  private static final double METERS_PER_DEGREE = 111_320.0;
  private static final double MIN_MYSQL_LONGITUDE = Math.nextUp(-180.0);
  // Mở biên nhỏ để MBRContains không loại điểm đúng biên sau khi MySQL đổi sang radian.
  private static final double PADDING_DEGREES = 1e-8;

  static CandidateBounds around(GeoPoint center, double radius) {
    double angular = radius / Geo.EARTH_RADIUS_M;
    double deltaLat =
        Math.max(radius / METERS_PER_DEGREE, Math.toDegrees(angular)) + PADDING_DEGREES;
    double south = Math.max(-90, center.lat() - deltaLat);
    double north = Math.min(90, center.lat() + deltaLat);
    if (south == -90 || north == 90) {
      // Vòng tròn chứa cực chạm mọi kinh tuyến.
      return new CandidateBounds(south, north, List.of());
    }

    double cosLat = Math.cos(Math.toRadians(center.lat()));
    double deltaLng =
        Math.max(
                radius / (METERS_PER_DEGREE * cosLat),
                Math.toDegrees(Math.asin(Math.min(1.0, Math.sin(angular) / cosLat))))
            + PADDING_DEGREES;
    double west = center.lng() - deltaLng;
    double east = center.lng() + deltaLng;
    if (west <= -180) {
      return new CandidateBounds(
          south,
          north,
          List.of(
              new Box(west + 360, south, 180, north),
              new Box(MIN_MYSQL_LONGITUDE, south, east, north)));
    }
    if (east > 180) {
      return new CandidateBounds(
          south,
          north,
          List.of(
              new Box(west, south, 180, north),
              new Box(MIN_MYSQL_LONGITUDE, south, east - 360, north)));
    }
    return new CandidateBounds(south, north, List.of(new Box(west, south, east, north)));
  }

  record Box(double west, double south, double east, double north) {
    boolean atDateline() {
      return west == MIN_MYSQL_LONGITUDE || east == 180;
    }

    String polygon() {
      return "POLYGON(("
          + west
          + " "
          + south
          + ","
          + east
          + " "
          + south
          + ","
          + east
          + " "
          + north
          + ","
          + west
          + " "
          + north
          + ","
          + west
          + " "
          + south
          + "))";
    }
  }
}
