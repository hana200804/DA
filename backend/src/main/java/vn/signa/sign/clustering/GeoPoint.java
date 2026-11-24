package vn.signa.sign.clustering;

/** Toạ độ WGS 84 (SRID 4326), tính bằng độ. */
public record GeoPoint(double lat, double lng) {

  public GeoPoint {
    if (!(lat >= -90 && lat <= 90)) {
      throw new IllegalArgumentException("Vĩ độ ngoài [-90; 90]: " + lat);
    }
    if (!(lng >= -180 && lng <= 180)) {
      throw new IllegalArgumentException("Kinh độ ngoài [-180; 180]: " + lng);
    }
  }
}
