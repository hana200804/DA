package vn.signa.sign.clustering;

/** Cờ của đề xuất ({@code submissions.flags}). */
public enum SubmissionFlag {
  /** Track bị chia phiếu giữa các mã; {@code f = 0,5}. */
  SPLIT_VOTE,
  /** Người dùng sửa mã trước khi gửi; {@code p = 0,7}. */
  USER_EDITED
}
