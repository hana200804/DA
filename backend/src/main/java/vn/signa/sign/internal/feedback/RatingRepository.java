package vn.signa.sign.internal.feedback;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import vn.signa.sign.clustering.CodeWeight;
import vn.signa.sign.clustering.GeoPoint;
import vn.signa.sign.clustering.RatingValue;
import vn.signa.sign.clustering.SignState;
import vn.signa.sign.clustering.SignStatus;

@Repository
class RatingRepository {

  private final JdbcClient jdbc;

  RatingRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  Optional<SignState> lockPublished(String signId) {
    return jdbc.sql(
            """
            SELECT id, status, sign_type_code, category_code, confirm_count, deny_count,
                   moderator_boost, reviewed_at, version, confidence,
                   ST_Latitude(location) AS lat, ST_Longitude(location) AS lng
            FROM signs
            WHERE id = :sign AND status IN ('APPROVED', 'UNDER_REVIEW')
            FOR UPDATE
            """)
        .param("sign", signId)
        .query((rs, row) ->
            SignState.builder()
                .id(rs.getString("id"))
                .status(SignStatus.valueOf(rs.getString("status")))
                .signTypeCode(rs.getString("sign_type_code"))
                .categoryCode(rs.getString("category_code"))
                .location(new GeoPoint(rs.getDouble("lat"), rs.getDouble("lng")))
                .confirmCount(rs.getInt("confirm_count"))
                .denyCount(rs.getInt("deny_count"))
                .moderatorBoost(rs.getBigDecimal("moderator_boost"))
                .confidence(rs.getBigDecimal("confidence"))
                .reviewedAt(FeedbackSql.instant(rs, "reviewed_at"))
                .version(rs.getInt("version"))
                .build())
        .optional();
  }

  boolean hasSubmission(String signId, String userId) {
    return jdbc.sql(
            "SELECT EXISTS (SELECT 1 FROM submissions WHERE sign_id = :sign AND user_id = :user)")
        .param("sign", signId)
        .param("user", userId)
        .query(Boolean.class)
        .single();
  }

  Map<String, CodeWeight> codeWeights(String signId) {
    return jdbc.sql(
            """
            SELECT sign_type_code, weight, submitter_count
            FROM sign_code_weights WHERE sign_id = :sign
            """)
        .param("sign", signId)
        .query(rs -> {
          Map<String, CodeWeight> weights = new HashMap<>();
          while (rs.next()) {
            weights.put(
                rs.getString("sign_type_code"),
                new CodeWeight(rs.getBigDecimal("weight"), rs.getInt("submitter_count")));
          }
          return weights;
        });
  }

  Optional<PreviousRating> previous(String signId, String userId) {
    return jdbc.sql(
            "SELECT value, updated_at FROM sign_ratings WHERE sign_id = :sign AND user_id = :user")
        .param("sign", signId)
        .param("user", userId)
        .query((rs, row) ->
            new PreviousRating(
                RatingValue.valueOf(rs.getString("value")), FeedbackSql.instant(rs, "updated_at")))
        .optional();
  }

  int countDeniesSinceReview(String signId, Instant reviewedAt) {
    return jdbc.sql(
            """
            SELECT COUNT(*) FROM sign_ratings
            WHERE sign_id = :sign AND value = 'DENY'
              AND (:reviewedAt IS NULL OR updated_at > :reviewedAt)
            """)
        .param("sign", signId)
        .param("reviewedAt", FeedbackSql.utc(reviewedAt))
        .query(Integer.class)
        .single();
  }

  Map<String, BigDecimal> weightsSinceReview(String signId, Instant reviewedAt) {
    if (reviewedAt == null) return Map.of();
    return jdbc.sql(
            """
            SELECT sign_type_code, SUM(weight) AS weight
            FROM submissions
            WHERE sign_id = :sign AND weight IS NOT NULL AND received_at > :reviewedAt
            GROUP BY sign_type_code
            """)
        .param("sign", signId)
        .param("reviewedAt", FeedbackSql.utc(reviewedAt))
        .query(rs -> {
          Map<String, BigDecimal> weights = new HashMap<>();
          while (rs.next()) weights.put(rs.getString("sign_type_code"), rs.getBigDecimal("weight"));
          return weights;
        });
  }

  void upsert(String signId, String userId, RatingValue value, Instant now) {
    jdbc.sql(
            """
            INSERT INTO sign_ratings (sign_id, user_id, value, created_at, updated_at)
            VALUES (:sign, :user, :value, :now, :now)
            ON DUPLICATE KEY UPDATE value = :value, updated_at = :now
            """)
        .param("sign", signId)
        .param("user", userId)
        .param("value", value.name())
        .param("now", FeedbackSql.utc(now))
        .update();
  }

  void updateSign(SignState sign, Instant now) {
    jdbc.sql(
            """
            UPDATE signs
            SET status = :status, confidence = :confidence, suggestion = :suggestion,
                confirm_count = :confirm, deny_count = :deny, deny_since_review = :sinceReview,
                version = :version, updated_at = :now
            WHERE id = :sign
            """)
        .param("sign", sign.id())
        .param("status", sign.status().name())
        .param("confidence", sign.confidence())
        .param("suggestion", sign.suggestion() == null ? null : sign.suggestion().name())
        .param("confirm", sign.confirmCount())
        .param("deny", sign.denyCount())
        .param("sinceReview", sign.denySinceReview())
        .param("version", sign.version())
        .param("now", FeedbackSql.utc(now))
        .update();
  }

  record PreviousRating(RatingValue value, Instant updatedAt) {}
}
