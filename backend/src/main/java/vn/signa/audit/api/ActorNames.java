package vn.signa.audit.api;

import java.util.Collection;
import java.util.Map;

/**
 * Tên hiển thị của người thực hiện, cho cột {@code actorName} của lịch sử (docs/07 §8). Module
 * identity cài đặt qua {@code identity.api.UserDirectory}; cổng nằm ở đây vì identity đã phụ thuộc
 * {@code audit.api}, nên audit không được phụ thuộc ngược lại (không chu trình giữa module).
 */
public interface ActorNames {

  /** Tên hiển thị theo id tài khoản; id không tồn tại thì vắng mặt trong map. */
  Map<String, String> displayNames(Collection<String> ids);
}
