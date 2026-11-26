package vn.signa.sign.clustering;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.Set;

/**
 * Đề xuất cần gom cụm.
 *
 * @param confidence độ tin cậy mô hình {@code p}, trong [0; 1]
 * @param headingDeg hướng xe 0–359, {@code null} khi không có (ảnh chụp, xe chậm)
 * @param receivedAt thời điểm server nhận, dùng cho chống lặp 12 giờ và hạn 30 ngày
 */
public record SubmissionInput(
    String id,
    String userId,
    String signTypeCode,
    String categoryCode,
    BigDecimal confidence,
    GeoPoint location,
    Integer headingDeg,
    Set<SubmissionFlag> flags,
    Instant receivedAt) {

  public SubmissionInput {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(userId, "userId");
    Objects.requireNonNull(signTypeCode, "signTypeCode");
    Objects.requireNonNull(categoryCode, "categoryCode");
    Objects.requireNonNull(confidence, "confidence");
    Objects.requireNonNull(location, "location");
    Objects.requireNonNull(receivedAt, "receivedAt");
    if (confidence.signum() < 0 || confidence.compareTo(BigDecimal.ONE) > 0) {
      throw new IllegalArgumentException("confidence ngoài [0; 1]: " + confidence);
    }
    if (headingDeg != null && (headingDeg < 0 || headingDeg > 359)) {
      throw new IllegalArgumentException("headingDeg ngoài 0–359: " + headingDeg);
    }
    flags = flags == null ? Set.of() : Set.copyOf(flags);
  }
}
