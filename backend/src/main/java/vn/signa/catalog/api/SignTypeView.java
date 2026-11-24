package vn.signa.catalog.api;

import static java.nio.charset.StandardCharsets.UTF_8;

import org.springframework.web.util.UriUtils;

/**
 * Một loại biển như module khác thấy.
 *
 * @param iconKey khoá ảnh minh hoạ trong bucket {@code sign-icons}, hoặc {@code null}
 * @param labelId {@code id} lớp trong danh mục nhãn; {@code null} nếu loại chỉ nhập tay
 * @param labelMapVersion phiên bản danh mục nhãn có lớp này; {@code null} nếu chỉ nhập tay
 */
public record SignTypeView(
    String code,
    String categoryCode,
    String name,
    String description,
    Integer speedKmh,
    String iconKey,
    Integer labelId,
    Integer labelMapVersion,
    boolean active) {

  /** Đường dẫn công khai của ảnh minh hoạ, nginx phục vụ {@code /icons/} từ {@code sign-icons}. */
  public static String iconUrl(String iconKey) {
    return iconKey == null ? null : "/icons/" + UriUtils.encodePathSegment(iconKey, UTF_8);
  }

  /** Mô hình nhận diện được loại này. */
  public boolean recognizable() {
    return labelId != null;
  }

  public String iconUrl() {
    return iconUrl(iconKey);
  }
}
