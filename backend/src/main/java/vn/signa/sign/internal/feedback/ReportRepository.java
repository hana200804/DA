package vn.signa.sign.internal.feedback;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import vn.signa.sign.internal.feedback.ReportService.Reason;
import vn.signa.sign.internal.feedback.ReportService.Resolution;
import vn.signa.sign.internal.feedback.ReportService.Status;

@Repository
class ReportRepository {

  private static final String REPORT_COLUMNS =
      """
      SELECT id, sign_id, user_id, reason, description, photo_key, status, resolution,
             resolution_note, created_at, resolved_at
      FROM sign_reports
      """;
  private static final String ORDER = " ORDER BY created_at DESC, id DESC";
  private static final String PAGE = " LIMIT :size OFFSET :offset";

  private final JdbcClient jdbc;

  ReportRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  boolean lockPublishedSign(String signId) {
    return jdbc.sql(
            """
            SELECT id FROM signs
            WHERE id = :sign AND status IN ('APPROVED', 'UNDER_REVIEW')
            FOR SHARE
            """)
        .param("sign", signId)
        .query(String.class)
        .optional()
        .isPresent();
  }

  void insert(
      String id,
      String signId,
      String userId,
      ReportMetadata metadata,
      String photoKey,
      Instant now) {
    jdbc.sql(
            """
            INSERT INTO sign_reports (id, sign_id, user_id, reason, description, photo_key, status, created_at)
            VALUES (:id, :sign, :user, :reason, :description, :photoKey, 'PENDING', :now)
            """)
        .param("id", id)
        .param("sign", signId)
        .param("user", userId)
        .param("reason", metadata.reason().name())
        .param("description", metadata.description())
        .param("photoKey", photoKey)
        .param("now", FeedbackSql.utc(now))
        .update();
  }

  List<ReportRow> findMine(String userId, int page, int size) {
    return jdbc.sql(REPORT_COLUMNS + " WHERE user_id = :user" + ORDER + PAGE)
        .param("user", userId)
        .param("size", size)
        .param("offset", (long) page * size)
        .query(ReportRepository::report)
        .list();
  }

  long countMine(String userId) {
    return jdbc.sql("SELECT COUNT(*) FROM sign_reports WHERE user_id = :user")
        .param("user", userId)
        .query(Long.class)
        .single();
  }

  private static ReportRow report(ResultSet rs, int row) throws SQLException {
    String resolution = rs.getString("resolution");
    return new ReportRow(
        rs.getString("id"),
        rs.getString("sign_id"),
        rs.getString("user_id"),
        Reason.valueOf(rs.getString("reason")),
        rs.getString("description"),
        rs.getString("photo_key"),
        Status.valueOf(rs.getString("status")),
        resolution == null ? null : Resolution.valueOf(resolution),
        rs.getString("resolution_note"),
        FeedbackSql.instant(rs, "created_at"),
        FeedbackSql.instant(rs, "resolved_at"));
  }

  record ReportRow(
      String id,
      String signId,
      String userId,
      Reason reason,
      String description,
      String photoKey,
      Status status,
      Resolution resolution,
      String resolutionNote,
      Instant createdAt,
      Instant resolvedAt) {}
}
