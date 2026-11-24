package vn.signa.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionTemplate;
import vn.signa.audit.api.AuditEntityType;
import vn.signa.audit.api.AuditEntry;
import vn.signa.audit.api.AuditRecorder;
import vn.signa.support.IntegrationTest;

/** Ghi {@code audit_logs} trong giao dịch của bên gọi (NFR-11). */
class AuditRecorderIT extends IntegrationTest {

  @Autowired AuditRecorder recorder;
  @Autowired TransactionTemplate tx;
  @Autowired JdbcTemplate jdbc;

  @Test
  void recordsInsideCallerTransactionWithRequestId() {
    String entityId = UUID.randomUUID().toString();
    MDC.put("requestId", "it-audit-1");
    try {
      tx.executeWithoutResult(s ->
          recorder.record(
              AuditEntry.bySystem(
                  AuditEntityType.SIGN,
                  entityId,
                  "CREATED",
                  null,
                  Map.of("status", "PENDING"),
                  "thử")));
    } finally {
      MDC.remove("requestId");
    }

    Map<String, Object> row =
        jdbc.queryForMap(
            "SELECT action, actor_type, actor_id, request_id, after_value, reason"
                + " FROM audit_logs WHERE entity_id = ?",
            entityId);
    assertThat(row)
        .containsEntry("action", "CREATED")
        .containsEntry("actor_type", "SYSTEM")
        .containsEntry("request_id", "it-audit-1")
        .containsEntry("reason", "thử");
    assertThat(row.get("actor_id")).isNull();
    assertThat(row.get("after_value").toString()).contains("PENDING");
  }

  @Test
  void rollsBackWithCaller() {
    String entityId = UUID.randomUUID().toString();
    tx.executeWithoutResult(s -> {
      recorder.record(
          AuditEntry.bySystem(AuditEntityType.SIGN, entityId, "CREATED", null, null, null));
      s.setRollbackOnly();
    });

    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM audit_logs WHERE entity_id = ?", Integer.class, entityId))
        .isZero();
  }

  @Test
  void refusesToRunOutsideTransaction() {
    assertThatThrownBy(() ->
            recorder.record(
                AuditEntry.bySystem(AuditEntityType.SIGN, "s-none", "CREATED", null, null, null)))
        .isInstanceOf(IllegalTransactionStateException.class);
  }
}
