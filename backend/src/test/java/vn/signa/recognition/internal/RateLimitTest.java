package vn.signa.recognition.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import vn.signa.shared.error.ApiException;
import vn.signa.shared.error.ErrorCode;

class RateLimitTest {

  @Test
  void guestIsLimitedByIp() {
    RateLimit limit = RateLimit.forCaller(Optional.empty(), "203.0.113.7", 30, 10);

    assertThat(limit).isEqualTo(new RateLimit("rl:recognize:ip:203.0.113.7", 30));
  }

  @Test
  void signedInUserIsLimitedByAccountNotIp() {
    RateLimit limit =
        RateLimit.forCaller(
            Optional.of("8b5c9a0e-1d2f-4c3b-9a8e-7f6d5c4b3a21"), "203.0.113.7", 30, 10);

    assertThat(limit)
        .isEqualTo(new RateLimit("rl:recognize:user:8b5c9a0e-1d2f-4c3b-9a8e-7f6d5c4b3a21", 10));
  }

  @Test
  void ipv6AddressIsUsedVerbatim() {
    assertThat(RateLimit.forCaller(Optional.empty(), "2001:db8::1", 30, 10).key())
        .isEqualTo("rl:recognize:ip:2001:db8::1");
  }

  @Test
  void callWithinLimitPasses() {
    RecognitionRateLimiter limiter = limiterReturning(List.of(30L, 12L));

    limiter.acquire(new RateLimit("rl:recognize:ip:x", 30));
  }

  @Test
  void callOverLimitIsRejectedWithRemainingTtl() {
    RecognitionRateLimiter limiter = limiterReturning(List.of(31L, 12L));

    assertThatThrownBy(() -> limiter.acquire(new RateLimit("rl:recognize:ip:x", 30)))
        .isInstanceOfSatisfying(
            ApiException.class,
            e -> {
              assertThat(e.code()).isEqualTo(ErrorCode.RATE_LIMITED);
              assertThat(e.retryAfterSeconds()).isEqualTo(12);
            });
  }

  @Test
  void retryAfterIsAtLeastOneSecond() {
    assertThat(RecognitionRateLimiter.retryAfter(0)).isEqualTo(1);
    assertThat(RecognitionRateLimiter.retryAfter(-2)).isEqualTo(1);
    assertThat(RecognitionRateLimiter.retryAfter(60)).isEqualTo(60);
  }

  @Test
  @SuppressWarnings("unchecked")
  void redisOutageFailsOpen() {
    StringRedisTemplate redis = mock(StringRedisTemplate.class);
    when(redis.execute(any(RedisScript.class), anyList(), anyString()))
        .thenThrow(new RedisConnectionFailureException("down"));

    new RecognitionRateLimiter(redis).acquire(new RateLimit("rl:recognize:ip:x", 30));
  }

  @SuppressWarnings("unchecked")
  private static RecognitionRateLimiter limiterReturning(List<Long> result) {
    StringRedisTemplate redis = mock(StringRedisTemplate.class);
    when(redis.execute(any(RedisScript.class), anyList(), anyString())).thenReturn(result);
    return new RecognitionRateLimiter(redis);
  }
}
