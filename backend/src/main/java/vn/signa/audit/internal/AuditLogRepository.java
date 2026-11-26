package vn.signa.audit.internal;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Đọc {@code audit_logs}. Module không có câu lệnh sửa hay xoá lịch sử (FR-23). */
@Repository
class AuditLogRepository {

  /** Một dòng {@code audit_logs}; {@code before}, {@code after} là chuỗi JSON như trong CSDL. */
  record Row(
      long id,
      String entityType,
      String entityId,
      String action,
      String actorType,
      String actorId,
      String before,
      String after,
      String reason,
      String requestId,
      Instant createdAt) {}

  /** Mệnh đề {@code WHERE} ghép từ các điều kiện có mặt, cùng tham số. */
  record Where(String sql, Map<String, Object> params) {

    static Where of(AuditLogQuery q) {
      List<String> conditions = new ArrayList<>();
      Map<String, Object> params = new LinkedHashMap<>();
      if (q.entityType() != null) {
        conditions.add("entity_type = :entityType");
        params.put("entityType", q.entityType().name());
      }
      if (q.entityId() != null) {
        conditions.add("entity_id = :entityId");
        params.put("entityId", q.entityId());
      }
      if (q.actorId() != null) {
        conditions.add("actor_id = :actorId");
        params.put("actorId", q.actorId());
      }
      if (q.action() != null) {
        conditions.add("action = :action");
        params.put("action", q.action());
      }
      if (q.from() != null) {
        conditions.add("created_at >= :from");
        params.put("from", Timestamp.from(q.from()));
      }
      if (q.to() != null) {
        conditions.add("created_at <= :to");
        params.put("to", Timestamp.from(q.to()));
      }
      String sql = conditions.isEmpty() ? "" : " WHERE " + String.join(" AND ", conditions);
      return new Where(sql, params);
    }
  }

  private final JdbcClient jdbc;

  AuditLogRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  long count(AuditLogQuery q) {
    Where where = Where.of(q);
    return jdbc.sql("SELECT COUNT(*) FROM audit_logs" + where.sql())
        .params(where.params())
        .query(Long.class)
        .single();
  }

  List<Row> page(AuditLogQuery q) {
    Where where = Where.of(q);
    return jdbc.sql(
            """
            SELECT id, entity_type, entity_id, action, actor_type, actor_id,
                   before_value, after_value, reason, request_id, created_at
            FROM audit_logs\
            """
                + where.sql()
                + " ORDER BY created_at DESC, id DESC LIMIT :limit OFFSET :offset")
        .params(where.params())
        .param("limit", q.size())
        .param("offset", q.offset())
        .query(AuditLogRepository::map)
        .list();
  }

  private static Row map(ResultSet rs, int row) throws SQLException {
    return new Row(
        rs.getLong("id"),
        rs.getString("entity_type"),
        rs.getString("entity_id"),
        rs.getString("action"),
        rs.getString("actor_type"),
        rs.getString("actor_id"),
        rs.getString("before_value"),
        rs.getString("after_value"),
        rs.getString("reason"),
        rs.getString("request_id"),
        rs.getTimestamp("created_at").toInstant());
  }
}
