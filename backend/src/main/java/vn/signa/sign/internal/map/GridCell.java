package vn.signa.sign.internal.map;

/** Ô lưới gom biển khi vùng có hơn 500 biển (docs/07 §6). */
public final class GridCell {

  /**
   * Số ô tối đa theo mỗi trục. Client gửi {@code zoom} khớp khung nhìn nên khung rộng {@code w}
   * pixel chỉ có khoảng {@code w / 32} ô; 128 đủ cho màn hình 4K. Yêu cầu có {@code bbox} rộng mà
   * {@code zoom} cao sẽ được nới cạnh ô để số điểm gộp trả về có giới hạn.
   */
  static final int MAX_CELLS_PER_AXIS = 128;

  private GridCell() {}

  /** Cạnh ô theo độ: {@code 360 / 2^zoom / 8}, khoảng 1/8 ô bản đồ 256 px ở mức zoom đó. */
  public static double sizeDeg(int zoom) {
    if (zoom < MapQueryParser.MIN_ZOOM || zoom > MapQueryParser.MAX_ZOOM) {
      throw new IllegalArgumentException("zoom ngoài [0, 22]: " + zoom);
    }
    return 360.0 / (1L << zoom) / 8;
  }

  /** Cạnh ô theo zoom, nới ra khi cần để vùng có tối đa 128 × 128 ô. */
  public static double sizeDeg(int zoom, BoundingBox bbox) {
    double span = Math.max(bbox.maxLng() - bbox.minLng(), bbox.maxLat() - bbox.minLat());
    return Math.max(sizeDeg(zoom), span / MAX_CELLS_PER_AXIS);
  }
}
