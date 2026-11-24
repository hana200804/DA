package vn.signa.sign.internal;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;
import tools.jackson.databind.json.JsonMapper;
import vn.signa.sign.clustering.GeoPoint;

final class SignSql {

  private SignSql() {}

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

  static String flags(JsonMapper mapper, Set<? extends Enum<?>> flags) {
    return mapper.writeValueAsString(flags.stream().map(Enum::name).sorted().toList());
  }

  static <E extends Enum<E>> Set<E> flags(JsonMapper mapper, String json, Class<E> type) {
    return Arrays.stream(mapper.readValue(json, String[].class))
        .map(name -> Enum.valueOf(type, name))
        .collect(Collectors.toUnmodifiableSet());
  }
}
