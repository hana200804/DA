package vn.signa.sign.internal.feedback;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import vn.signa.sign.clustering.SignStatus;
import vn.signa.sign.internal.feedback.ReportService.Reason;
import vn.signa.sign.internal.feedback.ReportService.Resolution;
import vn.signa.sign.internal.feedback.ReportService.Status;
import vn.signa.sign.internal.map.MapResult.LatLng;

@Repository
class ReportRepository {

  private static final String REPORT_COLUMNS =
      """
      SELECT id, sign_id, user_id, reason, description, photo_key, status, resolution,
             resolution_note, created_at, resolved_at
      FROM sign_reports
      """;
  private static final String FILTER =
      " WHERE status = :status AND (:reason IS NULL OR reason = :reason)";
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

  List<SignRow> findSigns(Status status, Reason reason, int page, int size) {
    return filtered(
            """
            SELECT s.id, s.sign_type_code, s.status, s.version,
                   ST_Latitude(s.location) AS lat, ST_Longitude(s.location) AS lng
            FROM (
              SELECT sign_id, MAX(created_at) AS newest FROM sign_reports
            """
                + FILTER
                + " GROUP BY sign_id ORDER BY newest DESC, sign_id DESC"
                + PAGE
                + """
                ) AS report_groups JOIN signs s ON s.id = report_groups.sign_id
                ORDER BY report_groups.newest DESC, report_groups.sign_id DESC
                """,
            status,
            reason)
        .param("size", size)
        .param("offset", (long) page * size)
        .query((rs, row) ->
            new SignRow(
                rs.getString("id"),
                rs.getString("sign_type_code"),
                new LatLng(rs.getDouble("lat"), rs.getDouble("lng")),
                SignStatus.valueOf(rs.getString("status")),
                rs.getInt("version")))
        .list();
  }

  long countSigns(Status status, Reason reason) {
    return filtered("SELECT COUNT(DISTINCT sign_id) FROM sign_reports" + FILTER, status, reason)
        .query(Long.class)
        .single();
  }

  List<ReportRow> findForSigns(List<String> ids, Status status, Reason reason) {
    return filtered(REPORT_COLUMNS + FILTER + " AND sign_id IN (:ids)" + ORDER, status, reason)
        .param("ids", ids)
        .query(ReportRepository::report)
        .list();
  }

  Optional<PendingReport> lockPending(String id) {
    return jdbc.sql(
            """
            SELECT id, sign_id FROM sign_reports WHERE id = :id AND status = 'PENDING'
            FOR UPDATE
            """)
        .param("id", id)
        .query((rs, row) -> new PendingReport(rs.getString("id"), rs.getString("sign_id")))
        .optional();
  }

  boolean accept(String id, String userId, Resolution resolution, Instant now) {
    return jdbc.sql(
                """
                UPDATE sign_reports
                SET status = 'ACCEPTED', resolution = :resolution, resolved_by = :user, resolved_at = :now
                WHERE id = :id AND status = 'PENDING'
                """)
            .param("id", id)
            .param("user", userId)
            .param("resolution", resolution.name())
            .param("now", FeedbackSql.utc(now))
            .update()
        == 1;
  }

  boolean dismiss(String id, String userId, String note, Instant now) {
    return jdbc.sql(
                """
                UPDATE sign_reports
                SET status = 'DISMISSED', resolution_note = :note, resolved_by = :user, resolved_at = :now
                WHERE id = :id AND status = 'PENDING'
                """)
            .param("id", id)
            .param("note", note)
            .param("user", userId)
            .param("now", FeedbackSql.utc(now))
            .update()
        == 1;
  }

  Map<String, Boolean> photoReferences(List<String> keys, Instant retiredBefore) {
    return jdbc.sql(
            """
            SELECT photo_key, status <> 'PENDING' AND resolved_at <= :retiredBefore AS expired
            FROM sign_reports WHERE photo_key IN (:keys)
            """)
        .param("keys", keys)
        .param("retiredBefore", FeedbackSql.utc(retiredBefore))
        .query(rs -> {
          Map<String, Boolean> references = new HashMap<>();
          while (rs.next()) references.put(rs.getString("photo_key"), rs.getBoolean("expired"));
          return references;
        });
  }

  private JdbcClient.StatementSpec filtered(String sql, Status status, Reason reason) {
    return jdbc.sql(sql)
        .param("status", status.name())
        .param("reason", reason == null ? null : reason.name());
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

  record SignRow(String id, String code, LatLng location, SignStatus status, int version) {}

  record PendingReport(String id, String signId) {}
}
