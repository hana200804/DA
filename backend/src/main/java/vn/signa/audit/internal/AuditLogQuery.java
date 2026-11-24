package vn.signa.audit.internal;

import java.time.Instant;
import java.util.List;
import vn.signa.audit.api.AuditEntityType;
import vn.signa.shared.error.ApiException;
import vn.signa.shared.error.ErrorCode;
import vn.signa.shared.error.FieldIssue;

/**
 * Bộ lọc của {@code GET /admin/audit-logs} (FR-34). Chuỗi rỗng coi như không lọc; {@code from},
 * {@code to} lấy cả hai đầu. Độ dài và phạm vi {@code page}, {@code size} kiểm ở controller.
 */
public record AuditLogQuery(
    AuditEntityType entityType,
    String entityId,
    String actorId,
    String action,
    Instant from,
    Instant to,
    int page,
    int size) {

  public AuditLogQuery {
    entityId = blankToNull(entityId);
    actorId = blankToNull(actorId);
    action = blankToNull(action);
    if (from != null && to != null && from.isAfter(to)) {
      throw new ApiException(
          ErrorCode.VALIDATION_ERROR,
          ErrorCode.VALIDATION_ERROR.defaultMessage(),
          List.of(new FieldIssue("from", "phải trước hoặc bằng to")),
          null);
    }
    if (page < 0 || size < 1) {
      throw new IllegalArgumentException("page phải ≥ 0, size phải ≥ 1");
    }
  }

  long offset() {
    return (long) page * size;
  }

  private static String blankToNull(String value) {
    if (value == null) return null;
    String trimmed = value.trim();
    return trimmed.isEmpty() ? null : trimmed;
  }
}
