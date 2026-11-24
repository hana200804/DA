package vn.signa.identity.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import vn.signa.shared.error.ApiException;
import vn.signa.shared.error.ErrorCode;

class LoginRateLimiterTest {

  private static final String KEY = LoginRateLimiter.loginKey("a@b.vn", "ip");

  private final MutableClock clock = new MutableClock(Instant.parse("2026-11-23T08:00:00Z"));
  private final LoginRateLimiter limiter = new LoginRateLimiter(new InMemoryAttemptCounter(clock));

  @Test
  void keysFollowDocs06() {
    assertThat(LoginRateLimiter.loginKey("a@b.vn", "10.0.0.1"))
        .isEqualTo("rl:login:a@b.vn:10.0.0.1");
    assertThat(LoginRateLimiter.passwordKey("u-1")).isEqualTo("rl:password:u-1");
  }

  @Test
  void tenAttemptsAllowedThenEleventhBlockedWithRetryAfter() {
    for (int i = 0; i < 10; i++) {
      assertThatCode(() -> limiter.acquire(KEY)).doesNotThrowAnyException();
    }
    clock.advance(Duration.ofMinutes(5));

    assertThatThrownBy(() -> limiter.acquire(KEY))
        .isInstanceOfSatisfying(
            ApiException.class,
            e -> {
              assertThat(e.code()).isEqualTo(ErrorCode.RATE_LIMITED);
              assertThat(e.retryAfterSeconds()).isEqualTo(600);
            });
  }

  @Test
  void windowIsFixedFromFirstAttemptAndExpires() {
    for (int i = 0; i < 10; i++) {
      limiter.acquire(KEY);
      clock.advance(Duration.ofMinutes(1));
    }
    assertThatThrownBy(() -> limiter.acquire(KEY)).isInstanceOf(ApiException.class);

    clock.advance(Duration.ofMinutes(5));
    assertThatCode(() -> limiter.acquire(KEY)).doesNotThrowAnyException();
  }

  @Test
  void countsPerKey() {
    for (int i = 0; i < 10; i++) {
      limiter.acquire(LoginRateLimiter.loginKey("a@b.vn", "ip-1"));
    }
    assertThatCode(() -> limiter.acquire(LoginRateLimiter.loginKey("a@b.vn", "ip-2")))
        .doesNotThrowAnyException();
    assertThatCode(() -> limiter.acquire(LoginRateLimiter.loginKey("c@d.vn", "ip-1")))
        .doesNotThrowAnyException();
  }

  @Test
  void resetClearsCounter() {
    for (int i = 0; i < 10; i++) {
      limiter.acquire(KEY);
    }
    limiter.reset(KEY);
    assertThatCode(() -> limiter.acquire(KEY)).doesNotThrowAnyException();
  }
}
