package vn.signa.sign.internal.map;

import org.springframework.jdbc.core.simple.JdbcClient;

/** Điều kiện bbox dùng chung cho bản đồ và hàng đợi phê duyệt. */
public final class BoundingBoxSql {

  private BoundingBoxSql() {}

  public static String predicate(BoundingBox bbox) {
    if (!bbox.touchesDateline()) {
      return "MBRContains(ST_GeomFromText(:polygon, 4326, 'axis-order=long-lat'), location)";
    }

    String latitude = "ST_Latitude(location) BETWEEN :minLat AND :maxLat";
    if (bbox.coversAllLongitudes()) return latitude;
    if (bbox.minLng() == -180) {
      // MySQL lưu kinh tuyến −180° dưới dạng +180°.
      return latitude + " AND (ST_Longitude(location) <= :maxLng OR ST_Longitude(location) = 180)";
    }
    return latitude + " AND ST_Longitude(location) >= :minLng";
  }

  public static void bind(JdbcClient.StatementSpec spec, BoundingBox bbox) {
    if (!bbox.touchesDateline()) {
      spec.param("polygon", bbox.toWkt());
      return;
    }
    spec.param("minLat", bbox.minLat()).param("maxLat", bbox.maxLat());
    if (!bbox.coversAllLongitudes()) {
      if (bbox.minLng() == -180) spec.param("maxLng", bbox.maxLng());
      else spec.param("minLng", bbox.minLng());
    }
  }
}
