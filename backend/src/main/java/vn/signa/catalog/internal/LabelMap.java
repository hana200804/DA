package vn.signa.catalog.internal;

import java.util.List;

/**
 * Nội dung {@code ai/data/labels.yaml} đã đọc. Trước khi qua {@link LabelMapValidator} các trường
 * có thể {@code null}; sau đó mọi trường bắt buộc đều có giá trị.
 *
 * @param line dòng (bắt đầu từ 1) của mục trong file, để báo lỗi
 */
public record LabelMap(Integer version, List<Category> categories, List<LabelClass> classes) {

  public LabelMap {
    categories = List.copyOf(categories);
    classes = List.copyOf(classes);
  }

  /**
   * Một nhóm biển.
   *
   * @param key khoá trong file, ví dụ {@code prohibitory}
   * @param code mã lưu trong CSDL, là {@code key} viết hoa
   * @param sortOrder thứ tự trong file, bắt đầu từ 1
   */
  public record Category(String key, String code, String name, int sortOrder, int line) {}

  /** Một lớp của mô hình, ứng với một loại biển. */
  public record LabelClass(
      Integer id, String code, String categoryKey, String name, Integer speedKmh, int line) {}
}
