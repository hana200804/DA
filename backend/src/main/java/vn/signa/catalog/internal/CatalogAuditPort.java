package vn.signa.catalog.internal;

import java.util.Map;

/**
 * Cổng ghi lịch sử của catalog. Tạm thời thay cho {@code audit.api.AuditRecorder} của lát 1, nhánh
 * này chưa có. Phần 2 của lát 2 thay {@link LoggingCatalogAuditPort} bằng adapter gọi {@code
 * AuditRecorder.record(AuditEntry.bySystem(...))}. Phải gọi trong giao dịch của thay đổi.
 */
public interface CatalogAuditPort {

  /** Khớp {@code audit_logs.entity_type}. */
  enum Entity {
    SIGN_TYPE,
    SIGN_CATEGORY
  }

  String CREATED = "CREATED";
  String UPDATED = "UPDATED";

  /**
   * Ghi một thay đổi do hệ thống gây ra ({@code actor_type = SYSTEM}).
   *
   * @param before các trường trước khi đổi, {@code null} khi tạo mới
   * @param after các trường sau khi đổi
   */
  void recordSystem(
      Entity entity, String code, String action, Map<String, ?> before, Map<String, ?> after);
}
