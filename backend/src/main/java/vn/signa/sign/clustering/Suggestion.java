package vn.signa.sign.clustering;

/** Gợi ý cho kiểm duyệt viên ({@code signs.suggestion}, FR-21). */
public enum Suggestion {
  /** Nên duyệt. */
  APPROVE,
  /** Cần xem kỹ. */
  INSPECT,
  /** Chờ thêm. */
  WAIT
}
