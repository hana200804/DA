package vn.signa.sign.internal.review;

import vn.signa.sign.clustering.GeoPoint;

/**
 * Thay đổi kiểm duyệt viên yêu cầu. {@code null} nghĩa là giữ nguyên, trừ hướng: {@code headingSet}
 * cho biết có đặt hướng, và {@code headingDeg = null} khi đó là xoá hướng.
 */
record SignChange(String signTypeCode, GeoPoint location, boolean headingSet, Integer headingDeg) {

  static final SignChange NONE = new SignChange(null, null, false, null);

  boolean isEmpty() {
    return signTypeCode == null && location == null && !headingSet;
  }
}
