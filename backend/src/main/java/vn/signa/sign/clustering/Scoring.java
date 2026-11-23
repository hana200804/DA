package vn.signa.sign.clustering;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;

/** Mã dẫn đầu, tỉ lệ mã thứ hai và điểm tin cậy Beta với tiên nghiệm Jeffreys. */
public final class Scoring {

  /** Tham số tiên nghiệm Jeffreys {@code Beta(0,5; 0,5)}. */
  public static final BigDecimal PRIOR = new BigDecimal("0.5");

  /** Số chữ số thập phân khi lưu điểm ({@code DECIMAL(5,4)}). */
  public static final int SCALE = 4;

  private Scoring() {}

  /**
   * Mã dẫn đầu {@code c*}. Biển {@code PENDING}: mã có {@code W_c} lớn nhất; hoà thì mã có {@code
   * submitter_count} lớn hơn, rồi mã đến trước (đề xuất sớm nhất theo {@code receivedAt}), rồi thứ
   * tự từ điển. Biển khác: mã kiểm duyệt viên đã chốt.
   */
  public static String leadingCode(SignState sign) {
    if (sign.status() != SignStatus.PENDING || sign.codeWeights().isEmpty()) {
      return sign.signTypeCode();
    }
    Map<String, Instant> firstSeen = new HashMap<>();
    for (MemberSubmission m : sign.members()) {
      firstSeen.merge(m.signTypeCode(), m.receivedAt(), (a, b) -> a.isBefore(b) ? a : b);
    }
    Comparator<Map.Entry<String, CodeWeight>> rank =
        Comparator.<Map.Entry<String, CodeWeight>, BigDecimal>comparing(e -> e.getValue().weight())
            .reversed()
            .thenComparing(e -> e.getValue().submitterCount(), Comparator.reverseOrder())
            .thenComparing(e -> firstSeen.getOrDefault(e.getKey(), Instant.MAX))
            .thenComparing(Map.Entry::getKey);
    return sign.codeWeights().entrySet().stream().min(rank).orElseThrow().getKey();
  }

  /** {@code max_{c≠c*} W_c}, trọng số mã đứng thứ hai. */
  public static BigDecimal secondWeight(SignState sign, String leading) {
    return sign.codeWeights().entrySet().stream()
        .filter(e -> !e.getKey().equals(leading))
        .map(e -> e.getValue().weight())
        .max(Comparator.naturalOrder())
        .orElse(BigDecimal.ZERO);
  }

  /** {@code s2 ≥ threshold}, so chính xác; tổng trọng số bằng 0 thì {@code s2 = 0}. */
  public static boolean secondShareAtLeast(SignState sign, String leading, BigDecimal threshold) {
    BigDecimal total = sign.totalWeight();
    if (total.signum() == 0) {
      return threshold.signum() <= 0;
    }
    return secondWeight(sign, leading).compareTo(threshold.multiply(total)) >= 0;
  }

  /**
   * Điểm {@code α / (α + β)}, làm tròn 4 chữ số, với {@code α = 0,5 + W_c* + R+ + M} và {@code β =
   * 0,5 + Σ_{c≠c*} W_c + R−}.
   */
  public static BigDecimal score(SignState sign, String leading) {
    BigDecimal leadingWeight = sign.weightOf(leading);
    BigDecimal alpha =
        PRIOR
            .add(leadingWeight)
            .add(BigDecimal.valueOf(sign.confirmCount()))
            .add(sign.moderatorBoost());
    BigDecimal beta =
        PRIOR
            .add(sign.totalWeight().subtract(leadingWeight))
            .add(BigDecimal.valueOf(sign.denyCount()));
    return alpha.divide(alpha.add(beta), SCALE, RoundingMode.HALF_UP);
  }
}
