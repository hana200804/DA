package vn.signa.sign.clustering;

import java.math.BigDecimal;
import java.util.Optional;

/** Gợi ý (FR-21), tự duyệt (FR-25) và rà soát lại (FR-22) theo {@code docs/04 §6}. */
public final class Suggestions {

  private Suggestions() {}

  /**
   * Gợi ý cho biển chờ kiểm duyệt; {@code null} với biển không chờ kiểm duyệt. Xét theo thứ tự: Cần
   * xem kỹ, Nên duyệt, Chờ thêm.
   *
   * @param score điểm đã làm tròn 4 chữ số
   */
  public static Suggestion suggest(
      SignState sign, String leading, BigDecimal score, ClusteringConfig config) {
    if (sign.status() != SignStatus.PENDING && sign.status() != SignStatus.UNDER_REVIEW) {
      return null;
    }
    if (sign.status() == SignStatus.UNDER_REVIEW
        || sign.flags().contains(SignFlag.DIFFERS_FROM_APPROVED)
        || Scoring.secondShareAtLeast(sign, leading, config.inspectSecondShare())) {
      return Suggestion.INSPECT;
    }
    if (score.compareTo(config.approveScore()) >= 0
        && sign.distinctSubmitters() >= config.approveMinSubmitters()) {
      return Suggestion.APPROVE;
    }
    return Suggestion.WAIT;
  }

  /**
   * Tự duyệt: bật, biển {@code PENDING} "Nên duyệt", điểm ≥ 0,85, {@code n ≥ 3}, {@code s2 < 0,1}.
   */
  public static boolean shouldAutoApprove(SignState sign, String leading, ClusteringConfig config) {
    return config.autoApproveEnabled()
        && sign.status() == SignStatus.PENDING
        && sign.suggestion() == Suggestion.APPROVE
        && sign.confidence().compareTo(config.autoApproveScore()) >= 0
        && sign.distinctSubmitters() >= config.autoApproveMinSubmitters()
        && !Scoring.secondShareAtLeast(sign, leading, config.autoApproveMaxSecondShare());
  }

  /**
   * Lý do rà soát lại khi biển {@code APPROVED} có điểm dưới ngưỡng hoặc đủ số đánh giá "không
   * đúng" kể từ lần duyệt gần nhất; rỗng nếu không cần.
   */
  public static Optional<String> reviewReason(SignState sign, ClusteringConfig config) {
    if (sign.status() != SignStatus.APPROVED) {
      return Optional.empty();
    }
    if (sign.confidence().compareTo(config.reviewScore()) < 0) {
      return Optional.of(
          "Điểm tin cậy " + sign.confidence().toPlainString() + " dưới " + config.reviewScore());
    }
    if (sign.denySinceReview() >= config.reviewDenies()) {
      return Optional.of(
          sign.denySinceReview() + " đánh giá \"không đúng\" kể từ lần duyệt gần nhất");
    }
    return Optional.empty();
  }
}
