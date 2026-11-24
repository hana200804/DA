package vn.signa.identity.internal;

import java.time.Duration;
import org.springframework.stereotype.Component;
import vn.signa.shared.error.ApiException;

/**
 * Giới hạn số lần thử mật khẩu: quá {@value #MAX_FAILURES} lần trong 15 phút thì {@code 429} kèm
 * {@code Retry-After} (docs/06 §10, NFR-10). Cửa sổ cố định, bắt đầu từ lần thử đầu.
 *
 * <p>Mỗi lần thử được giữ chỗ bằng một {@code INCR} trước khi so mật khẩu, nên nhiều request song
 * song cũng không vượt được giới hạn. Đúng mật khẩu thì xoá bộ đếm.
 */
@Component
class LoginRateLimiter {

  static final int MAX_FAILURES = 10;
  static final Duration WINDOW = Duration.ofMinutes(15);

  private final AttemptCounter counter;

  LoginRateLimiter(AttemptCounter counter) {
    this.counter = counter;
  }

  /** Giữ chỗ một lần thử; ném {@code RATE_LIMITED} nếu đã thử đủ {@value #MAX_FAILURES} lần. */
  void acquire(String key) {
    if (counter.increment(key, WINDOW) > MAX_FAILURES) {
      long seconds = Math.max(1, counter.remaining(key).toSeconds());
      throw ApiException.rateLimited((int) Math.min(seconds, WINDOW.toSeconds()));
    }
  }

  void reset(String key) {
    counter.reset(key);
  }

  /** Đăng nhập: theo email và IP (docs/06 §10). */
  static String loginKey(String email, String ip) {
    return "rl:login:" + email + ":" + ip;
  }

  /** Nhập sai mật khẩu hiện tại khi đổi mật khẩu: theo người dùng. */
  static String passwordKey(String userId) {
    return "rl:password:" + userId;
  }
}
