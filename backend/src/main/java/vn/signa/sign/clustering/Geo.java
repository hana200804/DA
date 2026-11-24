package vn.signa.sign.clustering;

/** Khoảng cách cầu và lệch hướng theo vòng tròn. */
public final class Geo {

  /** Bán kính mặc định của {@code ST_Distance_Sphere} trong MySQL 8, mét. */
  public static final double EARTH_RADIUS_M = 6_370_986.0;

  private Geo() {}

  /** Khoảng cách haversine giữa hai điểm, mét. */
  public static double distanceMeters(GeoPoint a, GeoPoint b) {
    double phi1 = Math.toRadians(a.lat());
    double phi2 = Math.toRadians(b.lat());
    double sinDPhi = Math.sin((phi2 - phi1) / 2);
    double sinDLambda = Math.sin(Math.toRadians(b.lng() - a.lng()) / 2);
    double h = sinDPhi * sinDPhi + Math.cos(phi1) * Math.cos(phi2) * sinDLambda * sinDLambda;
    return 2 * EARTH_RADIUS_M * Math.asin(Math.min(1, Math.sqrt(h)));
  }

  /** Lệch hướng {@code min(d, 360 − d)} với {@code d = |a − b|}, trong [0; 180]. */
  public static int headingDiff(int a, int b) {
    int d = Math.abs(Math.floorMod(a, 360) - Math.floorMod(b, 360));
    return Math.min(d, 360 - d);
  }

  /** Thiếu hướng ở một phía thì bỏ qua điều kiện hướng. */
  public static boolean headingsCompatible(Integer a, Integer b, int maxDiffDeg) {
    return a == null || b == null || headingDiff(a, b) <= maxDiffDeg;
  }
}
