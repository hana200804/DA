package vn.signa.identity.api;

import java.util.Collection;
import java.util.Map;

/** Tra tên hiển thị theo lô cho các module khác. */
public interface UserDirectory {

  /** Trả tên theo id. Id không tồn tại không có trong kết quả. */
  Map<String, String> displayNames(Collection<String> ids);
}
