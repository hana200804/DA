package vn.signa.catalog.internal;

import java.util.List;

/** {@code labels.yaml} sai. Thông điệp liệt kê mọi lỗi kèm số dòng. */
public class LabelsFileException extends RuntimeException {

  private final List<String> problems;

  public LabelsFileException(String source, List<String> problems) {
    super(source + " không hợp lệ:\n  - " + String.join("\n  - ", problems));
    this.problems = List.copyOf(problems);
  }

  public List<String> problems() {
    return problems;
  }
}
