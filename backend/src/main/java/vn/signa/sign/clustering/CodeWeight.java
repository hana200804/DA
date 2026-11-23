package vn.signa.sign.clustering;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * Một dòng {@code sign_code_weights}.
 *
 * @param weight tổng trọng số {@code W_c}
 * @param submitterCount số người khác nhau đề xuất mã này
 */
public record CodeWeight(BigDecimal weight, int submitterCount) {

  public CodeWeight {
    Objects.requireNonNull(weight, "weight");
    if (weight.signum() < 0) {
      throw new IllegalArgumentException("W_c âm: " + weight);
    }
    if (submitterCount < 0) {
      throw new IllegalArgumentException("submitterCount âm: " + submitterCount);
    }
  }
}
