package vn.signa.sign.internal.review;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.json.JsonMapper;
import vn.signa.sign.clustering.Geo;
import vn.signa.sign.clustering.GeoPoint;
import vn.signa.sign.internal.map.BoundingBoxSql;
import vn.signa.sign.internal.review.ReviewQueries.QueueQuery;

/** Đọc hàng đợi và chi tiết biển cho kiểm duyệt viên. */
@Repository
class ReviewQueryRepository {

  private static final String SIGN_COLUMNS =
      """
      SELECT id, sign_type_code, category_code, heading_deg, status, confidence, suggestion,
             distinct_submitters, flags, created_at, version, location_locked, related_sign_id,
             cover_crop_key, confirm_count, deny_count,
             ST_Latitude(location) AS lat, ST_Longitude(location) AS lng
      FROM signs
      """;

  /** "Nên duyệt" trước, rồi điểm giảm dần, rồi biển cũ trước; {@code id} để phân trang ổn định. */
  static final String QUEUE_ORDER =
      " ORDER BY CASE WHEN suggestion = 'APPROVE' THEN 0 ELSE 1 END, confidence DESC,"
          + " created_at ASC, id ASC";

  private static final double PADDING_DEGREES = 1e-7;

  private final JdbcClient jdbc;
  private final JsonMapper json;

  ReviewQueryRepository(JdbcClient jdbc, JsonMapper json) {
    this.jdbc = jdbc;
    this.json = json;
  }

  record SignRow(
      String id,
      String signTypeCode,
      String categoryCode,
      GeoPoint location,
      Integer headingDeg,
      String status,
      BigDecimal confidence,
      String suggestion,
      int distinctSubmitters,
      List<String> flags,
      Instant createdAt,
      int version,
      boolean locationLocked,
      String relatedSignId,
      String coverCropKey,
      int confirmCount,
      int denyCount) {}

  record SubmissionRow(
      String id,
      String signTypeCode,
      BigDecimal confidence,
      BigDecimal weight,
      String source,
      List<String> flags,
      Integer headingDeg,
      GeoPoint location,
      Instant receivedAt,
      String status,
      String cropKey) {}

  record NearbyRow(String id, String signTypeCode, GeoPoint location, String status) {}

  List<SignRow> queue(QueueQuery query) {
    return bind(SIGN_COLUMNS + where(query) + QUEUE_ORDER + " LIMIT :size OFFSET :offset", query)
        .param("size", query.size())
        .param("offset", query.offset())
        .query(this::sign)
        .list();
  }

  long count(QueueQuery query) {
    return bind("SELECT COUNT(*) FROM signs" + where(query), query).query(Long.class).single();
  }

  Optional<SignRow> find(String id) {
    return jdbc.sql(SIGN_COLUMNS + " WHERE id = :id").param("id", id).query(this::sign).optional();
  }

  /** {@code W_c} theo mã, trọng số giảm dần. */
  Map<String, BigDecimal> codeWeights(String signId) {
    Map<String, BigDecimal> weights = new LinkedHashMap<>();
    jdbc.sql(
            "SELECT sign_type_code, weight FROM sign_code_weights WHERE sign_id = :id"
                + " ORDER BY weight DESC, sign_type_code")
        .param("id", signId)
        .query(rs -> {
          weights.put(rs.getString("sign_type_code"), rs.getBigDecimal("weight"));
        });
    return weights;
  }

  List<SubmissionRow> submissions(String signId) {
    return jdbc.sql(
            """
            SELECT id, sign_type_code, confidence, weight, source, flags, heading_deg, received_at,
                   status, crop_key, ST_Latitude(location) AS lat, ST_Longitude(location) AS lng
            FROM submissions WHERE sign_id = :id ORDER BY received_at, id
            """)
        .param("id", signId)
        .query((rs, i) ->
            new SubmissionRow(
                rs.getString("id"),
                rs.getString("sign_type_code"),
                rs.getBigDecimal("confidence"),
                rs.getBigDecimal("weight"),
                rs.getString("source"),
                ReviewSql.flags(json, rs.getString("flags")),
                rs.getObject("heading_deg", Integer.class),
                ReviewSql.location(rs),
                ReviewSql.instant(rs, "received_at"),
                rs.getString("status"),
                rs.getString("crop_key")))
        .list();
  }

  /**
   * Biển {@code APPROVED}, {@code UNDER_REVIEW} cách {@code center} không quá {@code radius} mét,
   * không gồm {@code excludeId}, gần nhất trước. Lọc thô bằng {@code MBRContains}; gần cực hoặc
   * kinh tuyến 180° thì chỉ lọc theo vĩ độ.
   */
  List<NearbyRow> nearby(String excludeId, GeoPoint center, double radius, int limit) {
    double deltaLat = Math.toDegrees(radius / Geo.EARTH_RADIUS_M) * 1.001 + PADDING_DEGREES;
    double south = center.lat() - deltaLat;
    double north = center.lat() + deltaLat;
    double deltaLng = deltaLat / Math.cos(Math.toRadians(center.lat()));
    double west = center.lng() - deltaLng;
    double east = center.lng() + deltaLng;
    boolean simple = south > -89 && north < 89 && west > -180 && east < 180;
    String bounds =
        simple
            ? "MBRContains(ST_GeomFromText(:bounds, 4326, 'axis-order=long-lat'), location)"
            : "ST_Latitude(location) BETWEEN :south AND :north";
    String distance =
        "ST_Distance_Sphere(location, ST_GeomFromText(:point, 4326, 'axis-order=long-lat'),"
            + " :earthRadius)";
    JdbcClient.StatementSpec spec =
        jdbc.sql(
                "SELECT id, sign_type_code, status, ST_Latitude(location) AS lat,"
                    + " ST_Longitude(location) AS lng FROM signs"
                    + " WHERE id <> :id AND status IN ('APPROVED', 'UNDER_REVIEW') AND "
                    + bounds
                    + " AND "
                    + distance
                    + " <= :radius ORDER BY "
                    + distance
                    + ", id LIMIT :limit")
            .param("id", excludeId)
            .param("point", ReviewSql.point(center))
            .param("earthRadius", Geo.EARTH_RADIUS_M)
            .param("radius", radius)
            .param("limit", limit);
    spec =
        simple
            ? spec.param("bounds", polygon(west, Math.max(south, -90), east, Math.min(north, 90)))
            : spec.param("south", south).param("north", north);
    return spec.query((rs, i) ->
            new NearbyRow(
                rs.getString("id"),
                rs.getString("sign_type_code"),
                ReviewSql.location(rs),
                rs.getString("status")))
        .list();
  }

  static String polygon(double west, double south, double east, double north) {
    return "POLYGON(("
        + west
        + " "
        + south
        + ", "
        + east
        + " "
        + south
        + ", "
        + east
        + " "
        + north
        + ", "
        + west
        + " "
        + north
        + ", "
        + west
        + " "
        + south
        + "))";
  }

  private static String where(QueueQuery query) {
    StringBuilder sql = new StringBuilder(" WHERE status IN (:statuses)");
    if (query.suggestion() != null) sql.append(" AND suggestion = :suggestion");
    if (!query.categories().isEmpty()) sql.append(" AND category_code IN (:categories)");
    if (query.bbox() != null) sql.append(" AND ").append(BoundingBoxSql.predicate(query.bbox()));
    return sql.toString();
  }

  private JdbcClient.StatementSpec bind(String sql, QueueQuery query) {
    JdbcClient.StatementSpec spec =
        jdbc.sql(sql)
            .param("statuses", query.statuses().stream().map(Enum::name).sorted().toList());
    if (query.suggestion() != null) spec = spec.param("suggestion", query.suggestion().name());
    if (!query.categories().isEmpty()) spec = spec.param("categories", query.categories());
    if (query.bbox() != null) BoundingBoxSql.bind(spec, query.bbox());
    return spec;
  }

  private SignRow sign(ResultSet rs, int row) throws SQLException {
    return new SignRow(
        rs.getString("id"),
        rs.getString("sign_type_code"),
        rs.getString("category_code"),
        ReviewSql.location(rs),
        rs.getObject("heading_deg", Integer.class),
        rs.getString("status"),
        rs.getBigDecimal("confidence"),
        rs.getString("suggestion"),
        rs.getInt("distinct_submitters"),
        ReviewSql.flags(json, rs.getString("flags")),
        ReviewSql.instant(rs, "created_at"),
        rs.getInt("version"),
        rs.getBoolean("location_locked"),
        rs.getString("related_sign_id"),
        rs.getString("cover_crop_key"),
        rs.getInt("confirm_count"),
        rs.getInt("deny_count"));
  }
}
