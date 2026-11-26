package vn.signa.identity.api;

import java.util.Collection;
import java.util.Map;
import vn.signa.audit.api.ActorNames;

/**
 * Tra thông tin công khai của tài khoản cho module khác, để không module nào đọc bảng {@code users}
 * ngoài identity. Cũng là cài đặt của {@link ActorNames} cho lịch sử xử lý.
 */
public interface UserDirectory extends ActorNames {

  /**
   * Tên hiển thị theo id; id không tồn tại thì vắng mặt trong map. Gọi một lần cho cả trang thay vì
   * từng dòng.
   */
  @Override
  Map<String, String> displayNames(Collection<String> ids);
}
