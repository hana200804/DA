package vn.signa.sign.internal.review;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import vn.signa.sign.clustering.CodeWeight;
import vn.signa.sign.clustering.MemberSubmission;
import vn.signa.sign.clustering.MemberUpdate;
import vn.signa.sign.clustering.SignState;
import vn.signa.sign.clustering.SignStatus;
import vn.signa.sign.clustering.SubmissionStatus;
import vn.signa.sign.clustering.Suggestion;

/**
 * Ghi biển cho thao tác của kiểm duyệt viên. Khoá dòng {@code signs} trước khi đụng tới đề xuất
 * thành viên và trọng số, cùng thứ tự với worker gom cụm của lát 5, để hai bên tuần tự hoá trên
 * dòng biển.
 */
@Repository
class ReviewSignRepository {

  private final JdbcClient jdbc;

  ReviewSignRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  /** Đọc biển bằng {@code SELECT … FOR UPDATE}, kèm trọng số và đề xuất thành viên. */
  Optional<SignState> lock(String id) {
    return jdbc.sql(
            """
            SELECT id, status, sign_type_code, category_code, heading_deg, location_locked,
                   distinct_submitters, confirm_count, deny_count, deny_since_review,
                   moderator_boost, confidence, suggestion, related_sign_id, reviewed_at, version,
                   ST_Latitude(location) AS lat, ST_Longitude(location) AS lng
            FROM signs WHERE id = :id FOR UPDATE
            """)
        .param("id", id)
        .query(ReviewSignRepository::read)
        .optional()
        .map(sign -> sign.toBuilder().codeWeights(weights(id)).members(members(id)).build());
  }

  /**
   * {@code UPDATE … WHERE id = ? AND version = ?}, tăng {@code version}. Trả {@code false} nếu
   * không có dòng nào được cập nhật.
   *
   * @param reviewedBy người quyết định, hoặc {@code null} để giữ {@code reviewed_by}
   */
  boolean update(SignState sign, int expectedVersion, String reviewedBy, Instant now) {
    return jdbc.sql(
                """
                UPDATE signs SET
                  status = :status, sign_type_code = :code, category_code = :category,
                  location = ST_GeomFromText(:location, 4326, 'axis-order=long-lat'),
                  heading_deg = :heading, location_locked = :locked,
                  deny_since_review = :deniesSinceReview, moderator_boost = :boost,
                  confidence = :confidence, suggestion = :suggestion, reviewed_at = :reviewedAt,
                  reviewed_by = COALESCE(:reviewedBy, reviewed_by),
                  version = version + 1, updated_at = :now
                WHERE id = :id AND version = :expectedVersion
                """)
            .params(values(sign, reviewedBy, now))
            .param("expectedVersion", expectedVersion)
            .update()
        == 1;
  }

  void insert(SignState sign, String reviewedBy, Instant now) {
    jdbc.sql(
            """
            INSERT INTO signs
              (id, status, sign_type_code, category_code, location, heading_deg, location_locked,
               deny_since_review, moderator_boost, confidence, suggestion, reviewed_at, reviewed_by,
               version, created_at, updated_at)
            VALUES (:id, :status, :code, :category,
                    ST_GeomFromText(:location, 4326, 'axis-order=long-lat'), :heading, :locked,
                    :deniesSinceReview, :boost, :confidence, :suggestion, :reviewedAt, :reviewedBy,
                    :version, :now, :now)
            """)
        .params(values(sign, reviewedBy, now))
        .param("version", sign.version())
        .update();
  }

  void saveWeights(String signId, Map<String, CodeWeight> weights) {
    weights.forEach((code, weight) ->
        jdbc.sql(
                """
                INSERT INTO sign_code_weights (sign_id, sign_type_code, weight, submitter_count)
                VALUES (:sign, :code, :weight, :submitters) AS incoming
                ON DUPLICATE KEY UPDATE weight = incoming.weight
                """)
            .param("sign", signId)
            .param("code", code)
            .param("weight", weight.weight())
            .param("submitters", weight.submitterCount())
            .update());
  }

  void updateMembers(String signId, List<MemberUpdate> members) {
    for (MemberUpdate member : members) {
      jdbc.sql(
              """
              UPDATE submissions SET status = :status, reject_reason = :reason
              WHERE id = :id AND sign_id = :sign
              """)
          .param("status", member.status().name())
          .param("reason", member.rejectReason())
          .param("id", member.submissionId())
          .param("sign", signId)
          .update();
    }
  }

  private Map<String, CodeWeight> weights(String id) {
    Map<String, CodeWeight> weights = new HashMap<>();
    jdbc.sql(
            "SELECT sign_type_code, weight, submitter_count FROM sign_code_weights"
                + " WHERE sign_id = :id")
        .param("id", id)
        .query(rs -> {
          weights.put(
              rs.getString("sign_type_code"),
              new CodeWeight(rs.getBigDecimal("weight"), rs.getInt("submitter_count")));
        });
    return weights;
  }

  private List<MemberSubmission> members(String id) {
    List<MemberSubmission> members = new ArrayList<>();
    jdbc.sql(
            """
            SELECT id, user_id, sign_type_code, weight, received_at, status,
                   ST_Latitude(location) AS lat, ST_Longitude(location) AS lng
            FROM submissions WHERE sign_id = :id AND weight IS NOT NULL
            ORDER BY received_at, id
            """)
        .param("id", id)
        .query(rs -> {
          members.add(
              new MemberSubmission(
                  rs.getString("id"),
                  rs.getString("user_id"),
                  rs.getString("sign_type_code"),
                  rs.getBigDecimal("weight"),
                  ReviewSql.location(rs),
                  ReviewSql.instant(rs, "received_at"),
                  SubmissionStatus.valueOf(rs.getString("status"))));
        });
    return members;
  }

  private static Map<String, Object> values(SignState sign, String reviewedBy, Instant now) {
    Map<String, Object> values = new HashMap<>();
    values.put("id", sign.id());
    values.put("status", sign.status().name());
    values.put("code", sign.signTypeCode());
    values.put("category", sign.categoryCode());
    values.put("location", ReviewSql.point(sign.location()));
    values.put("heading", sign.headingDeg());
    values.put("locked", sign.locationLocked());
    values.put("deniesSinceReview", sign.denySinceReview());
    values.put("boost", sign.moderatorBoost());
    values.put("confidence", sign.confidence());
    values.put("suggestion", sign.suggestion() == null ? null : sign.suggestion().name());
    values.put("reviewedAt", ReviewSql.utc(sign.reviewedAt()));
    values.put("reviewedBy", reviewedBy);
    values.put("now", ReviewSql.utc(now));
    return values;
  }

  private static SignState read(ResultSet rs, int row) throws SQLException {
    String suggestion = rs.getString("suggestion");
    return SignState.builder()
        .id(rs.getString("id"))
        .status(SignStatus.valueOf(rs.getString("status")))
        .signTypeCode(rs.getString("sign_type_code"))
        .categoryCode(rs.getString("category_code"))
        .location(ReviewSql.location(rs))
        .headingDeg(rs.getObject("heading_deg", Integer.class))
        .locationLocked(rs.getBoolean("location_locked"))
        .distinctSubmitters(rs.getInt("distinct_submitters"))
        .confirmCount(rs.getInt("confirm_count"))
        .denyCount(rs.getInt("deny_count"))
        .denySinceReview(rs.getInt("deny_since_review"))
        .moderatorBoost(rs.getBigDecimal("moderator_boost"))
        .confidence(rs.getBigDecimal("confidence"))
        .suggestion(suggestion == null ? null : Suggestion.valueOf(suggestion))
        .relatedSignId(rs.getString("related_sign_id"))
        .reviewedAt(ReviewSql.instant(rs, "reviewed_at"))
        .version(rs.getInt("version"))
        .build();
  }
}
