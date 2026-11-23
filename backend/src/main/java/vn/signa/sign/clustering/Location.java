package vn.signa.sign.clustering;

import java.math.BigDecimal;
import java.util.Comparator;
import java.util.List;
import java.util.function.ToDoubleFunction;

/** Vị trí biển: trung vị có trọng số theo {@code w}, tính riêng vĩ độ và kinh độ. */
public final class Location {

  private Location() {}

  /**
   * Vị trí mới của biển từ các đề xuất mang mã dẫn đầu. Giữ nguyên khi kiểm duyệt viên đã chốt vị
   * trí hoặc khi các đề xuất đó không có trọng số.
   */
  public static GeoPoint of(SignState sign, String leading) {
    if (sign.locationLocked()) {
      return sign.location();
    }
    List<MemberSubmission> voters =
        sign.members().stream()
            .filter(m -> m.signTypeCode().equals(leading) && m.weight().signum() > 0)
            .toList();
    if (voters.isEmpty()) {
      return sign.location();
    }
    return new GeoPoint(
        weightedMedian(voters, m -> m.location().lat()),
        weightedMedian(voters, m -> m.location().lng()));
  }

  /**
   * Trung vị có trọng số dương: giá trị đầu tiên có trọng số cộng dồn vượt nửa tổng; cộng dồn đúng
   * bằng nửa tổng thì lấy trung bình với giá trị kế tiếp.
   */
  static double weightedMedian(
      List<MemberSubmission> voters, ToDoubleFunction<MemberSubmission> value) {
    List<MemberSubmission> sorted =
        voters.stream().sorted(Comparator.comparingDouble(value)).toList();
    BigDecimal total =
        sorted.stream().map(MemberSubmission::weight).reduce(BigDecimal.ZERO, BigDecimal::add);
    BigDecimal cumulative = BigDecimal.ZERO;
    for (int i = 0; i < sorted.size(); i++) {
      cumulative = cumulative.add(sorted.get(i).weight());
      int cmp = cumulative.multiply(BigDecimal.TWO).compareTo(total);
      if (cmp > 0) {
        return value.applyAsDouble(sorted.get(i));
      }
      if (cmp == 0) {
        return (value.applyAsDouble(sorted.get(i)) + value.applyAsDouble(sorted.get(i + 1))) / 2;
      }
    }
    throw new IllegalStateException("Tổng trọng số phải dương");
  }
}
