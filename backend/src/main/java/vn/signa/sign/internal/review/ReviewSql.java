package vn.signa.sign.internal.review;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import tools.jackson.databind.json.JsonMapper;
import vn.signa.sign.clustering.GeoPoint;

/** Chuyển đổi kiểu giữa Java và MySQL theo docs/06 §9, giống {@code sign.internal.SignSql}. */
final class ReviewSql {

  private ReviewSql() {}

  static String point(GeoPoint location) {
    // MySQL dùng khoảng kinh độ (-180, 180]. Hai biểu diễn này cùng một kinh tuyến.
    double lng = location.lng() == -180 ? 180 : location.lng();
    return "POINT(" + lng + " " + location.lat() + ")";
  }

  static GeoPoint location(ResultSet rs) throws SQLException {
    return new GeoPoint(rs.getDouble("lat"), rs.getDouble("lng"));
  }

  static LocalDateTime utc(Instant instant) {
    return instant == null ? null : LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
  }

  static Instant instant(ResultSet rs, String column) throws SQLException {
    LocalDateTime value = rs.getObject(column, LocalDateTime.class);
    return value == null ? null : value.toInstant(ZoneOffset.UTC);
  }

  static List<String> flags(JsonMapper json, String value) {
    return value == null
        ? List.of()
        : Arrays.stream(json.readValue(value, String[].class)).toList();
  }
}
