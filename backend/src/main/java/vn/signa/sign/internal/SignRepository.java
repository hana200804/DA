package vn.signa.sign.internal;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.json.JsonMapper;
import vn.signa.sign.clustering.Geo;
import vn.signa.sign.clustering.SignFlag;
import vn.signa.sign.clustering.SignState;
import vn.signa.sign.clustering.SignStatus;
import vn.signa.sign.clustering.SubmissionInput;
import vn.signa.sign.clustering.Suggestion;

@Repository
class SignRepository {

  private static final String CANDIDATES =
      """
      SELECT id, status, sign_type_code, category_code, heading_deg, location_locked,
             distinct_submitters, confirm_count, deny_count, moderator_boost,
             confidence, suggestion, flags, related_sign_id, reviewed_at, version,
             (SELECT COUNT(*) FROM sign_ratings r
              WHERE r.sign_id = signs.id AND r.value = 'DENY'
                AND (signs.reviewed_at IS NULL OR r.updated_at > signs.reviewed_at))
               AS deny_since_review,
             ST_Latitude(location) AS lat, ST_Longitude(location) AS lng
      FROM signs
      WHERE category_code = :category AND status <> 'REMOVED'
        AND (status <> 'REJECTED' OR reviewed_at >= :rejectedSince)
        AND ST_Distance_Sphere(location, ST_GeomFromText(:point, 4326, 'axis-order=long-lat'),
                               :earthRadius) <= :radius
        AND %s
      ORDER BY id FOR UPDATE
      """;

  private final JdbcClient jdbc;
  private final JsonMapper json;

  SignRepository(JdbcClient jdbc, JsonMapper json) {
    this.jdbc = jdbc;
    this.json = json;
  }

  List<SignState> candidates(SubmissionInput input, double radius, Instant rejectedSince) {
    CandidateBounds bounds = CandidateBounds.around(input.location(), radius);
    if (bounds.boxes().isEmpty()) {
      return candidateQuery(
              input, radius, rejectedSince, "ST_Latitude(location) BETWEEN :south AND :north")
          .param("south", bounds.south())
          .param("north", bounds.north())
          .query(this::read)
          .list();
    }
    Map<String, SignState> signs = new LinkedHashMap<>();
    for (var box : bounds.boxes()) {
      String relation = box.atDateline() ? "MBRCovers" : "MBRContains";
      candidateQuery(
              input,
              radius,
              rejectedSince,
              relation + "(ST_GeomFromText(:bounds, 4326, 'axis-order=long-lat'), location)")
          .param("bounds", box.polygon())
          .query(this::read)
          .list()
          .forEach(sign -> signs.putIfAbsent(sign.id(), sign));
    }
    return List.copyOf(signs.values());
  }

  private JdbcClient.StatementSpec candidateQuery(
      SubmissionInput input, double radius, Instant rejectedSince, String boundsPredicate) {
    return jdbc.sql(CANDIDATES.formatted(boundsPredicate))
        .param("category", input.categoryCode())
        .param("rejectedSince", SignSql.utc(rejectedSince))
        .param("point", SignSql.point(input.location()))
        .param("earthRadius", Geo.EARTH_RADIUS_M)
        .param("radius", radius);
  }

  void insert(SignState sign, String cover, Instant now) {
    jdbc.sql(
            """
            INSERT INTO signs
              (id, status, sign_type_code, category_code, location, heading_deg, location_locked,
               distinct_submitters, confirm_count, deny_count, deny_since_review, moderator_boost,
               confidence, suggestion, flags, related_sign_id, cover_crop_key, reviewed_at, version,
               created_at, updated_at)
            VALUES (:id, :status, :code, :category, ST_GeomFromText(:location, 4326, 'axis-order=long-lat'),
                    :heading, :locked, :submitters, :confirms, :denies, :deniesSinceReview, :boost,
                    :confidence, :suggestion, :flags, :related, :cover, :reviewed, :version, :now, :now)
            """)
        .params(values(sign, cover, now))
        .param("version", sign.version())
        .update();
  }

  void update(SignState sign, String cover, Instant now) {
    int changed =
        jdbc.sql(
                """
                UPDATE signs SET
                  status = :status, sign_type_code = :code, category_code = :category,
                  location = ST_GeomFromText(:location, 4326, 'axis-order=long-lat'),
                  heading_deg = :heading, location_locked = :locked, distinct_submitters = :submitters,
                  confirm_count = :confirms, deny_count = :denies, deny_since_review = :deniesSinceReview,
                  moderator_boost = :boost, confidence = :confidence, suggestion = :suggestion,
                  flags = :flags, related_sign_id = :related, cover_crop_key = :cover, reviewed_at = :reviewed,
                  version = version + 1, updated_at = :now
                WHERE id = :id AND version = :expectedVersion
                """)
            .params(values(sign, cover, now))
            .param("expectedVersion", sign.version() - 1)
            .update();
    if (changed != 1) throw new IllegalStateException("Phiên bản biển đã thay đổi: " + sign.id());
  }

  private Map<String, Object> values(SignState sign, String cover, Instant now) {
    Map<String, Object> values = new LinkedHashMap<>();
    values.put("id", sign.id());
    values.put("status", sign.status().name());
    values.put("code", sign.signTypeCode());
    values.put("category", sign.categoryCode());
    values.put("location", SignSql.point(sign.location()));
    values.put("heading", sign.headingDeg());
    values.put("locked", sign.locationLocked());
    values.put("submitters", sign.distinctSubmitters());
    values.put("confirms", sign.confirmCount());
    values.put("denies", sign.denyCount());
    values.put("deniesSinceReview", sign.denySinceReview());
    values.put("boost", sign.moderatorBoost());
    values.put("confidence", sign.confidence());
    values.put("suggestion", sign.suggestion() == null ? null : sign.suggestion().name());
    values.put("flags", SignSql.flags(json, sign.flags()));
    values.put("related", sign.relatedSignId());
    values.put("cover", cover);
    values.put("reviewed", SignSql.utc(sign.reviewedAt()));
    values.put("now", SignSql.utc(now));
    return values;
  }

  private SignState read(ResultSet rs, int row) throws SQLException {
    String suggestion = rs.getString("suggestion");
    return SignState.builder()
        .id(rs.getString("id"))
        .status(SignStatus.valueOf(rs.getString("status")))
        .signTypeCode(rs.getString("sign_type_code"))
        .categoryCode(rs.getString("category_code"))
        .location(SignSql.location(rs))
        .headingDeg(rs.getObject("heading_deg", Integer.class))
        .locationLocked(rs.getBoolean("location_locked"))
        .distinctSubmitters(rs.getInt("distinct_submitters"))
        .confirmCount(rs.getInt("confirm_count"))
        .denyCount(rs.getInt("deny_count"))
        .denySinceReview(rs.getInt("deny_since_review"))
        .moderatorBoost(rs.getBigDecimal("moderator_boost"))
        .confidence(rs.getBigDecimal("confidence"))
        .suggestion(suggestion == null ? null : Suggestion.valueOf(suggestion))
        .flags(SignSql.flags(json, rs.getString("flags"), SignFlag.class))
        .relatedSignId(rs.getString("related_sign_id"))
        .reviewedAt(SignSql.instant(rs, "reviewed_at"))
        .version(rs.getInt("version"))
        .build();
  }
}
