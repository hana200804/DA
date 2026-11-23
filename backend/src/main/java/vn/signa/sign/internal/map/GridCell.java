package vn.signa.sign.internal.map;

/** Ô lưới gom biển khi vùng có hơn 500 biển (docs/07 §6). */
public final class GridCell {

  private GridCell() {}

  /** Cạnh ô theo độ: {@code 360 / 2^zoom / 8}, khoảng 1/8 ô bản đồ 256 px ở mức zoom đó. */
  public static double sizeDeg(int zoom) {
    if (zoom < MapQueryParser.MIN_ZOOM || zoom > MapQueryParser.MAX_ZOOM) {
      throw new IllegalArgumentException("zoom ngoài [0, 22]: " + zoom);
    }
    return 360.0 / (1L << zoom) / 8;
  }
}
