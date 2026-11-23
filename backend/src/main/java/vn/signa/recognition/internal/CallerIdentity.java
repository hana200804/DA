package vn.signa.recognition.internal;

import java.util.Optional;

/**
 * Người gọi hiện tại, để chọn giới hạn tần suất. Lát 1 nối với principal của Spring Security; tới
 * lúc đó mọi người gọi đều là khách.
 */
public interface CallerIdentity {

  /** {@code users.id} của người dùng đã đăng nhập, rỗng nếu là khách. */
  Optional<String> userId();
}
