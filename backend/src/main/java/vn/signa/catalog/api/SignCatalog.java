package vn.signa.catalog.api;

import java.util.Optional;
import java.util.Set;

/**
 * Danh mục loại biển cho module khác (lát 3 nhận diện, lát 4 bản đồ, lát 5 đề xuất). Giữ chữ ký ổn
 * định: đổi thì báo các lát đó (docs/backend/ke-hoach/lat-2-danh-muc.md).
 */
public interface SignCatalog {

  /** Loại biển theo mã, kể cả loại đã ngừng dùng (xem {@link SignTypeView#active()}). */
  Optional<SignTypeView> findType(String code);

  /** Mã nhóm của loại biển, kể cả loại đã ngừng dùng; rỗng nếu không có mã này. */
  Optional<String> categoryOf(String code);

  /**
   * Loại đang dùng, có trong danh mục nhãn, và {@code label_map_version} của nó bằng đúng {@code
   * labelMapVersion}. Dùng để kiểm tra mã do mô hình trả về hoặc điện thoại gửi lên.
   */
  boolean isActiveRecognizable(String code, int labelMapVersion);

  /**
   * Các phiên bản danh mục nhãn của loại đang dùng và nhận diện được. Phiên bản không có ở đây thì
   * trả {@code 422 BUSINESS_RULE} (docs/07 §2).
   */
  Set<Integer> supportedLabelMapVersions();
}
