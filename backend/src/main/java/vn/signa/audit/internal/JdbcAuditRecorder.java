package vn.signa.audit.internal;

import java.sql.Timestamp;
import java.time.Clock;
import java.util.Map;
import org.slf4j.MDC;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;
import vn.signa.audit.api.AuditEntry;
import vn.signa.audit.api.AuditRecorder;

/** Ghi {@code audit_logs} bằng tài khoản {@code signa_app}, vốn chỉ có {@code SELECT, INSERT}. */
@Component
class JdbcAuditRecorder implements AuditRecorder {

  /** Trùng {@code RequestIdFilter.MDC_KEY}; audit không phụ thuộc gói web. */
  static final String REQUEST_ID_MDC_KEY = "requestId";

  private final JdbcClient jdbc;
  private final JsonMapper json;
  private final Clock clock;

  JdbcAuditRecorder(JdbcClient jdbc, JsonMapper json, Clock clock) {
    this.jdbc = jdbc;
    this.json = json;
    this.clock = clock;
  }

  @Override
  @Transactional(propagation = Propagation.MANDATORY)
  public void record(AuditEntry entry) {
    jdbc.sql(
            """
            INSERT INTO audit_logs (entity_type, entity_id, action, actor_type, actor_id,
                                    before_value, after_value, reason, request_id, created_at)
            VALUES (:entityType, :entityId, :action, :actorType, :actorId,
                    :before, :after, :reason, :requestId, :createdAt)
            """)
        .param("entityType", entry.entityType().name())
        .param("entityId", entry.entityId())
        .param("action", entry.action())
        .param("actorType", entry.actorType().name())
        .param("actorId", entry.actorId())
        .param("before", toJson(json, entry.before()))
        .param("after", toJson(json, entry.after()))
        .param("reason", entry.reason())
        .param("requestId", MDC.get(REQUEST_ID_MDC_KEY))
        .param("createdAt", Timestamp.from(clock.instant()))
        .update();
  }

  static String toJson(JsonMapper json, Map<String, ?> value) {
    return value == null ? null : json.writeValueAsString(value);
  }
}
