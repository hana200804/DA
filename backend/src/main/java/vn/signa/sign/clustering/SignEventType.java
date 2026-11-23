package vn.signa.sign.clustering;

/** Hành động lịch sử ({@code audit_logs.action}) mà lõi gom cụm sinh ra. */
public enum SignEventType {
  CREATED,
  LEADING_CODE_CHANGED,
  /** Vị trí trung vị đổi khi có đề xuất mới (FR-23, NFR-11). */
  LOCATION_RECOMPUTED,
  AUTO_APPROVED,
  MARKED_UNDER_REVIEW
}
