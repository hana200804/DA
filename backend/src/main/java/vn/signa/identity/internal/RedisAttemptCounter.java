package vn.signa.identity.internal;

import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

@Component
class RedisAttemptCounter implements AttemptCounter {

  private final StringRedisTemplate redis;

  RedisAttemptCounter(StringRedisTemplate redis) {
    this.redis = redis;
  }

  @Override
  public long get(String key) {
    String value = redis.opsForValue().get(key);
    return value == null ? 0 : Long.parseLong(value);
  }

  @Override
  public long increment(String key, Duration window) {
    Long count = redis.opsForValue().increment(key);
    // Đặt lại TTL nếu lần EXPIRE trước bị lỡ, để khoá không tồn tại mãi.
    if (count == null || count == 1 || redis.getExpire(key) == -1) {
      redis.expire(key, window);
    }
    return count == null ? 1 : count;
  }

  @Override
  public Duration remaining(String key) {
    Long seconds = redis.getExpire(key, TimeUnit.SECONDS);
    return seconds == null || seconds <= 0 ? Duration.ZERO : Duration.ofSeconds(seconds);
  }

  @Override
  public void reset(String key) {
    redis.delete(key);
  }
}
