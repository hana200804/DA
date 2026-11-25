package vn.signa.sign.internal.map;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import vn.signa.sign.clustering.RatingValue;
import vn.signa.sign.internal.map.MapResult.LatLng;
import vn.signa.sign.internal.map.MapResult.MapCluster;
import vn.signa.sign.internal.map.MapResult.MapSign;

/** Truy vấn biển công khai trên bảng {@code signs} theo docs/06 §9. */
@Repository
public class MapSignRepository {

  /** Trạng thái hiển thị trên bản đồ và chi tiết công khai (FR-05). */
  static final String PUBLISHED = "status IN ('APPROVED', 'UNDER_REVIEW')";

  private static final String SIGNS_SQL =
      """
      SELECT id, sign_type_code, category_code,
             ST_Latitude(location) AS lat, ST_Longitude(location) AS lng,
             heading_deg, status, confidence
      FROM signs
      """;

  private static final String CLUSTERS_SQL =
      """
      SELECT AVG(ST_Latitude(location)) AS lat, AVG(ST_Longitude(location)) AS lng, COUNT(*) AS n
      FROM signs
      """;

  private static final String CLUSTERS_GROUP =
      " GROUP BY FLOOR(ST_Longitude(location) / :cell), FLOOR(ST_Latitude(location) / :cell)";

  private static final String DETAIL_SQL =
      """
      SELECT s.id, s.sign_type_code, s.category_code,
             ST_Latitude(s.location) AS lat, ST_Longitude(s.location) AS lng,
             s.heading_deg, s.status, s.confidence, s.confirm_count, s.deny_count,
             s.updated_at, s.cover_crop_key, r.value AS my_rating
      FROM signs s
      LEFT JOIN sign_ratings r ON r.sign_id = s.id AND r.user_id = :user
      WHERE s.id = :id AND
      """
          + PUBLISHED;

  private final JdbcClient jdbc;

  public MapSignRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  /** Tối đa {@code limit} biển công khai trong vùng, khớp bộ lọc. */
  public List<MapSign> findSigns(MapQuery query, int limit) {
    return bind(SIGNS_SQL + where(query) + " LIMIT :limit", query)
        .param("limit", limit)
        .query(MapSignRepository::mapSign)
        .list();
  }

  /** Gom mọi biển khớp trong vùng theo ô vuông cạnh {@code cellDeg} độ. */
  public List<MapCluster> countByCell(MapQuery query, double cellDeg) {
    return bind(CLUSTERS_SQL + where(query) + CLUSTERS_GROUP, query)
        .param("cell", cellDeg)
        .query((rs, i) ->
            new MapCluster(new LatLng(rs.getDouble("lat"), rs.getDouble("lng")), rs.getLong("n")))
        .list();
  }

  public Optional<DetailRow> findPublished(String id) {
    return findPublished(id, null).map(RatedDetailRow::sign);
  }

  Optional<RatedDetailRow> findPublished(String id, String userId) {
    return jdbc.sql(DETAIL_SQL)
        .param("id", id)
        .param("user", userId)
        .query((rs, i) -> {
          var sign =
              new DetailRow(
                  rs.getString("id"),
                  rs.getString("sign_type_code"),
                  rs.getString("category_code"),
                  location(rs),
                  rs.getObject("heading_deg", Integer.class),
                  rs.getString("status"),
                  rs.getDouble("confidence"),
                  rs.getInt("confirm_count"),
                  rs.getInt("deny_count"),
                  utc(rs.getObject("updated_at", LocalDateTime.class)),
                  rs.getString("cover_crop_key"));
          String value = rs.getString("my_rating");
          return new RatedDetailRow(sign, value == null ? null : RatingValue.valueOf(value));
        })
        .optional();
  }

  record RatedDetailRow(DetailRow sign, RatingValue myRating) {}

  /** Bản ghi chi tiết; khoá ảnh được dịch vụ đổi thành URL ký. */
  public record DetailRow(
      String id,
      String signTypeCode,
      String categoryCode,
      LatLng location,
      Integer headingDeg,
      String status,
      double confidence,
      int confirmCount,
      int denyCount,
      Instant updatedAt,
      String coverCropKey) {}

  private static String where(MapQuery query) {
    StringBuilder sql =
        new StringBuilder(
            " WHERE MBRContains(ST_GeomFromText(:polygon, 4326, 'axis-order=long-lat'), location)"
                + " AND "
                + PUBLISHED);
    if (!query.categories().isEmpty()) sql.append(" AND category_code IN (:categories)");
    if (!query.signTypeCodes().isEmpty()) sql.append(" AND sign_type_code IN (:signTypeCodes)");
    return sql.toString();
  }

  private JdbcClient.StatementSpec bind(String sql, MapQuery query) {
    JdbcClient.StatementSpec spec = jdbc.sql(sql).param("polygon", query.bbox().toWkt());
    if (!query.categories().isEmpty()) spec = spec.param("categories", query.categories());
    if (!query.signTypeCodes().isEmpty()) {
      spec = spec.param("signTypeCodes", query.signTypeCodes());
    }
    return spec;
  }

  private static MapSign mapSign(ResultSet rs, int i) throws SQLException {
    return new MapSign(
        rs.getString("id"),
        rs.getString("sign_type_code"),
        rs.getString("category_code"),
        location(rs),
        rs.getObject("heading_deg", Integer.class),
        rs.getString("status"),
        rs.getDouble("confidence"));
  }

  private static LatLng location(ResultSet rs) throws SQLException {
    return new LatLng(rs.getDouble("lat"), rs.getDouble("lng"));
  }

  /** Cột {@code DATETIME(3)} lưu giờ UTC (kết nối đặt {@code connectionTimeZone=UTC}). */
  private static Instant utc(LocalDateTime value) {
    return value.toInstant(ZoneOffset.UTC);
  }
}
