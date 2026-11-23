package vn.signa.sign.clustering;

/** Hành động lịch sử ({@code audit_logs.action}) mà lõi gom cụm sinh ra. */
public enum SignEventType {
  CREATED,
  LEADING_CODE_CHANGED,
  AUTO_APPROVED,
  MARKED_UNDER_REVIEW
}
