package vn.signa.identity.internal;

import java.time.Duration;
import org.springframework.stereotype.Component;
import vn.signa.shared.error.ApiException;

/**
 * Sai quá {@value #MAX_FAILURES} lần trong 15 phút theo {@code rl:login:{email}:{ip}} thì {@code
 * 429} kèm {@code Retry-After} (docs/06 §10, NFR-10). Cửa sổ cố định, bắt đầu từ lần sai đầu.
 */
@Component
class LoginRateLimiter {

  static final int MAX_FAILURES = 10;
  static final Duration WINDOW = Duration.ofMinutes(15);

  private final AttemptCounter counter;

  LoginRateLimiter(AttemptCounter counter) {
    this.counter = counter;
  }

  /** Ném {@code RATE_LIMITED} nếu đã sai đủ {@value #MAX_FAILURES} lần trong cửa sổ. */
  void checkAllowed(String email, String ip) {
    String key = key(email, ip);
    if (counter.get(key) >= MAX_FAILURES) {
      long seconds = Math.max(1, counter.remaining(key).toSeconds());
      throw ApiException.rateLimited((int) Math.min(seconds, WINDOW.toSeconds()));
    }
  }

  void recordFailure(String email, String ip) {
    counter.increment(key(email, ip), WINDOW);
  }

  void reset(String email, String ip) {
    counter.reset(key(email, ip));
  }

  static String key(String email, String ip) {
    return "rl:login:" + email + ":" + ip;
  }
}
