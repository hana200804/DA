package vn.signa.sign.clustering;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.util.List;

/** Trọng số đề xuất {@code w = p × k × f} ({@code docs/04 §6}). */
public final class Weights {

  /** Số chữ số thập phân khi lưu {@code w} và {@code W_c}. */
  public static final int SCALE = 4;

  private Weights() {}

  /**
   * Trọng số của đề xuất khi gán vào biển có các thành viên {@code members}.
   *
   * <p>{@code k = 0} khi cùng người đã có một đề xuất có trọng số trên biển này cách dưới 12 giờ
   * theo {@code receivedAt}. Cửa sổ tính cả hai phía để đúng cả khi đề xuất cũ được xử lý muộn.
   */
  public static BigDecimal of(
      SubmissionInput submission, List<MemberSubmission> members, ClusteringConfig config) {
    if (contributedRecently(submission, members, config.antiRepeatWindow())) {
      return BigDecimal.ZERO.setScale(SCALE);
    }
    BigDecimal p =
        submission.flags().contains(SubmissionFlag.USER_EDITED)
            ? config.userEditedConfidence()
            : submission.confidence();
    BigDecimal f =
        submission.flags().contains(SubmissionFlag.SPLIT_VOTE)
            ? config.splitVoteFactor()
            : BigDecimal.ONE;
    return p.multiply(f).setScale(SCALE, RoundingMode.HALF_UP);
  }

  static boolean contributedRecently(
      SubmissionInput submission, List<MemberSubmission> members, Duration window) {
    return members.stream()
        .anyMatch(m ->
            m.userId().equals(submission.userId())
                && m.weight().signum() > 0
                && Duration.between(m.receivedAt(), submission.receivedAt()).abs().compareTo(window)
                    < 0);
  }
}
