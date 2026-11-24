package vn.signa.recognition.internal;

import java.util.Optional;

/**
 * Người gọi hiện tại, để chọn giới hạn tần suất: theo tài khoản nếu đã đăng nhập, theo IP nếu là
 * khách.
 */
public interface CallerIdentity {

  /** {@code users.id} của người dùng đã đăng nhập, rỗng nếu là khách. */
  Optional<String> userId();
}
