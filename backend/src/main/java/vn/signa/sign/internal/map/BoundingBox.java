package vn.signa.sign.internal.map;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * Vùng đang xem theo độ WGS84, không vắt qua kinh tuyến 180°. Bất biến do {@link MapQueryParser}
 * kiểm tra trước khi tạo.
 */
public record BoundingBox(double minLng, double minLat, double maxLng, double maxLat) {

  /**
   * Cạnh đông–tây dài nhất của đa giác. Trong SRID 4326 MySQL nối hai đỉnh bằng đường trắc địa ngắn
   * nhất, nên một cạnh vượt 180° kinh độ sẽ đi vòng phía bên kia Trái Đất. Chia nhỏ để vùng rộng
   * (zoom thấp) vẫn đúng chiều.
   */
  static final double MAX_EDGE_LNG = 90;

  boolean touchesDateline() {
    return minLng == -180 || maxLng == 180;
  }

  boolean coversAllLongitudes() {
    return minLng == -180 && maxLng == 180;
  }

  /** Đa giác WKT theo thứ tự trục kinh độ trước, ngược chiều kim đồng hồ, khép kín. */
  public String toWkt() {
    int steps = Math.max(1, (int) Math.ceil((maxLng - minLng) / MAX_EDGE_LNG));
    List<Double> lngs = new ArrayList<>(steps + 1);
    for (int i = 0; i < steps; i++) {
      lngs.add(minLng + (maxLng - minLng) * i / steps);
    }
    lngs.add(maxLng);

    List<String> ring = new ArrayList<>(2 * lngs.size() + 1);
    for (double lng : lngs) {
      ring.add(point(lng, minLat));
    }
    for (int i = lngs.size() - 1; i >= 0; i--) {
      ring.add(point(lngs.get(i), maxLat));
    }
    ring.add(point(minLng, minLat));
    return "POLYGON((" + String.join(", ", ring) + "))";
  }

  private static String point(double lng, double lat) {
    return plain(lng) + " " + plain(lat);
  }

  /** Số thập phân thường, không dạng {@code 1.0E-4}. */
  private static String plain(double value) {
    if (value == 0) return "0";
    return BigDecimal.valueOf(value).stripTrailingZeros().toPlainString();
  }
}
