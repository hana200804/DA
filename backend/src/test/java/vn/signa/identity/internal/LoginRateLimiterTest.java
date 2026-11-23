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

  private final MutableClock clock = new MutableClock(Instant.parse("2026-11-23T08:00:00Z"));
  private final LoginRateLimiter limiter = new LoginRateLimiter(new InMemoryAttemptCounter(clock));

  @Test
  void keyFollowsDocs06() {
    assertThat(LoginRateLimiter.key("a@b.vn", "10.0.0.1")).isEqualTo("rl:login:a@b.vn:10.0.0.1");
  }

  @Test
  void tenFailuresAllowedThenBlockedWithRetryAfter() {
    for (int i = 0; i < 10; i++) {
      assertThatCode(() -> limiter.checkAllowed("a@b.vn", "ip")).doesNotThrowAnyException();
      limiter.recordFailure("a@b.vn", "ip");
    }
    clock.advance(Duration.ofMinutes(5));

    assertThatThrownBy(() -> limiter.checkAllowed("a@b.vn", "ip"))
        .isInstanceOfSatisfying(
            ApiException.class,
            e -> {
              assertThat(e.code()).isEqualTo(ErrorCode.RATE_LIMITED);
              assertThat(e.retryAfterSeconds()).isEqualTo(600);
            });
  }

  @Test
  void windowIsFixedFromFirstFailureAndExpires() {
    for (int i = 0; i < 10; i++) {
      limiter.recordFailure("a@b.vn", "ip");
      clock.advance(Duration.ofMinutes(1));
    }
    assertThatThrownBy(() -> limiter.checkAllowed("a@b.vn", "ip")).isInstanceOf(ApiException.class);

    clock.advance(Duration.ofMinutes(5));
    assertThatCode(() -> limiter.checkAllowed("a@b.vn", "ip")).doesNotThrowAnyException();
  }

  @Test
  void countsPerEmailAndIp() {
    for (int i = 0; i < 10; i++) {
      limiter.recordFailure("a@b.vn", "ip-1");
    }
    assertThatCode(() -> limiter.checkAllowed("a@b.vn", "ip-2")).doesNotThrowAnyException();
    assertThatCode(() -> limiter.checkAllowed("c@d.vn", "ip-1")).doesNotThrowAnyException();
  }

  @Test
  void resetClearsCounter() {
    for (int i = 0; i < 10; i++) {
      limiter.recordFailure("a@b.vn", "ip");
    }
    limiter.reset("a@b.vn", "ip");
    assertThatCode(() -> limiter.checkAllowed("a@b.vn", "ip")).doesNotThrowAnyException();
  }
}
