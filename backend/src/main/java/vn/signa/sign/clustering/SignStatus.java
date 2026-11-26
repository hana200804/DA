package vn.signa.sign.clustering;

/** Trạng thái biển ({@code signs.status}). */
public enum SignStatus {
  PENDING,
  APPROVED,
  UNDER_REVIEW,
  REJECTED,
  REMOVED;

  /** Biển đang hiển thị trên bản đồ, mã do kiểm duyệt viên chốt. */
  boolean isPublished() {
    return this == APPROVED || this == UNDER_REVIEW;
  }
}
