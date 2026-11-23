package vn.signa.audit.api;

/** Loại đối tượng của một dòng lịch sử, khớp ENUM {@code audit_logs.entity_type} (docs/06 §8). */
public enum AuditEntityType {
  SIGN,
  REPORT,
  SIGN_TYPE,
  SIGN_CATEGORY,
  USER
}
