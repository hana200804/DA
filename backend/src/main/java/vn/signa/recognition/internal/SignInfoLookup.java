package vn.signa.recognition.internal;

import java.util.Collection;
import java.util.Map;

/**
 * Tên, ý nghĩa, ảnh minh hoạ của loại biển để bổ sung vào kết quả nhận diện. Lát 2 nối với {@code
 * catalog.api.SignCatalog}; tới lúc đó các trường này là {@code null}.
 */
public interface SignInfoLookup {

  /** Tra theo lô; mã không có trong danh mục thì không có trong kết quả. */
  Map<String, SignInfo> lookup(Collection<String> signTypeCodes);

  record SignInfo(String name, String description, String iconUrl) {}
}
