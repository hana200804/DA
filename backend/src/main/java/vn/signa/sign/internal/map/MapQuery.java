package vn.signa.sign.internal.map;

import java.util.List;

/**
 * Tham số bản đồ đã kiểm tra. Danh sách lọc rỗng nghĩa là không lọc; gửi cả hai thì biển phải khớp
 * cả nhóm lẫn mã (docs/07 §6).
 */
public record MapQuery(
    BoundingBox bbox, List<String> categories, List<String> signTypeCodes, int zoom) {

  public MapQuery {
    categories = List.copyOf(categories);
    signTypeCodes = List.copyOf(signTypeCodes);
  }
}
