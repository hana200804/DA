package vn.signa.audit.api;

import java.util.Map;
import java.util.Objects;

/**
 * Một dòng lịch sử xử lý (docs/06 §8). {@code before}, {@code after} chỉ chứa các trường thay đổi
 * và được ghi thành JSON. {@code requestId} không nằm ở đây: bộ ghi lấy từ MDC của request.
 *
 * @param actorId id tài khoản khi {@code actorType = USER}; {@code null} khi {@code SYSTEM}
 */
public record AuditEntry(
    AuditEntityType entityType,
    String entityId,
    String action,
    ActorType actorType,
    String actorId,
    Map<String, ?> before,
    Map<String, ?> after,
    String reason) {

  public static final int MAX_ACTION = 40;
  public static final int MAX_REASON = 1000;

  public AuditEntry {
    Objects.requireNonNull(entityType, "entityType");
    Objects.requireNonNull(actorType, "actorType");
    require(entityId != null && !entityId.isBlank() && entityId.length() <= 36, "entityId");
    require(action != null && !action.isBlank() && action.length() <= MAX_ACTION, "action");
    require(reason == null || reason.length() <= MAX_REASON, "reason");
    if (actorType == ActorType.SYSTEM) {
      require(actorId == null, "actorId phải rỗng khi actorType = SYSTEM");
    } else {
      require(actorId != null && !actorId.isBlank(), "actorId bắt buộc khi actorType = USER");
    }
  }

  /** Thay đổi do hệ thống gây ra, không có người thực hiện. */
  public static AuditEntry bySystem(
      AuditEntityType entityType,
      String entityId,
      String action,
      Map<String, ?> before,
      Map<String, ?> after,
      String reason) {
    return new AuditEntry(
        entityType, entityId, action, ActorType.SYSTEM, null, before, after, reason);
  }

  /** Thay đổi do một tài khoản thực hiện. */
  public static AuditEntry byUser(
      String actorId,
      AuditEntityType entityType,
      String entityId,
      String action,
      Map<String, ?> before,
      Map<String, ?> after,
      String reason) {
    return new AuditEntry(
        entityType, entityId, action, ActorType.USER, actorId, before, after, reason);
  }

  private static void require(boolean ok, String what) {
    if (!ok) {
      throw new IllegalArgumentException("AuditEntry không hợp lệ: " + what);
    }
  }
}
