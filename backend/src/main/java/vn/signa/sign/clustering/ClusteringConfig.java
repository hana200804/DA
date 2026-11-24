package vn.signa.sign.clustering;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.Objects;

/**
 * Ngưỡng gom cụm và gợi ý. Giá trị mặc định đúng {@code docs/04 §6}; ADR-0004 dự kiến hiệu chỉnh
 * lại trên tuyến val nên các ngưỡng là tham số.
 *
 * @param radiusMeters bán kính gom cụm
 * @param maxHeadingDiffDeg lệch hướng tối đa
 * @param rejectedWindow thời gian biển bị từ chối còn chặn đề xuất cùng mã
 * @param antiRepeatWindow mỗi người góp trọng số tối đa một lần trong khoảng này cho một biển
 * @param userEditedConfidence {@code p} của đề xuất {@code USER_EDITED}
 * @param splitVoteFactor {@code f} của đề xuất {@code SPLIT_VOTE}
 * @param inspectSecondShare "Cần xem kỹ" khi {@code s2} đạt ngưỡng này
 * @param approveScore "Nên duyệt" khi điểm đạt ngưỡng này
 * @param approveMinSubmitters và số người khác nhau đạt ngưỡng này
 * @param autoApproveEnabled bật tự duyệt (FR-25), mặc định tắt
 * @param autoApproveScore điểm tối thiểu để tự duyệt
 * @param autoApproveMinSubmitters số người tối thiểu để tự duyệt
 * @param autoApproveMaxSecondShare tự duyệt khi {@code s2} nhỏ hơn ngưỡng này
 * @param reviewScore biển đã duyệt có điểm nhỏ hơn ngưỡng này thì rà soát lại (FR-22)
 * @param reviewDenies hoặc có từng này đánh giá "không đúng" kể từ lần duyệt gần nhất
 */
public record ClusteringConfig(
    double radiusMeters,
    int maxHeadingDiffDeg,
    Duration rejectedWindow,
    Duration antiRepeatWindow,
    BigDecimal userEditedConfidence,
    BigDecimal splitVoteFactor,
    BigDecimal inspectSecondShare,
    BigDecimal approveScore,
    int approveMinSubmitters,
    boolean autoApproveEnabled,
    BigDecimal autoApproveScore,
    int autoApproveMinSubmitters,
    BigDecimal autoApproveMaxSecondShare,
    BigDecimal reviewScore,
    int reviewDenies) {

  /** Ngưỡng của {@code docs/04 §6}, tự duyệt tắt. */
  public static final ClusteringConfig DEFAULTS =
      new ClusteringConfig(
          20.0,
          45,
          Duration.ofDays(30),
          Duration.ofHours(12),
          new BigDecimal("0.7"),
          new BigDecimal("0.5"),
          new BigDecimal("0.3"),
          new BigDecimal("0.8"),
          2,
          false,
          new BigDecimal("0.85"),
          3,
          new BigDecimal("0.1"),
          new BigDecimal("0.5"),
          3);

  public ClusteringConfig {
    Objects.requireNonNull(rejectedWindow, "rejectedWindow");
    Objects.requireNonNull(antiRepeatWindow, "antiRepeatWindow");
    Objects.requireNonNull(userEditedConfidence, "userEditedConfidence");
    Objects.requireNonNull(splitVoteFactor, "splitVoteFactor");
    Objects.requireNonNull(inspectSecondShare, "inspectSecondShare");
    Objects.requireNonNull(approveScore, "approveScore");
    Objects.requireNonNull(autoApproveScore, "autoApproveScore");
    Objects.requireNonNull(autoApproveMaxSecondShare, "autoApproveMaxSecondShare");
    Objects.requireNonNull(reviewScore, "reviewScore");
    if (!(radiusMeters > 0)) {
      throw new IllegalArgumentException("radiusMeters phải dương");
    }
    if (userEditedConfidence.signum() < 0 || splitVoteFactor.signum() < 0) {
      throw new IllegalArgumentException("p và f không được âm");
    }
  }

  public ClusteringConfig withAutoApprove(boolean enabled) {
    return new ClusteringConfig(
        radiusMeters,
        maxHeadingDiffDeg,
        rejectedWindow,
        antiRepeatWindow,
        userEditedConfidence,
        splitVoteFactor,
        inspectSecondShare,
        approveScore,
        approveMinSubmitters,
        enabled,
        autoApproveScore,
        autoApproveMinSubmitters,
        autoApproveMaxSecondShare,
        reviewScore,
        reviewDenies);
  }
}
