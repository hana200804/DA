package vn.signa.recognition.internal;

import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;
import vn.signa.shared.error.ApiException;

/**
 * Giới hạn tần suất cửa sổ cố định 60 giây (docs/backend/thiet-ke-trien-khai.md §5.3). {@code
 * INCR}, {@code EXPIRE}, {@code TTL} chạy trong một Lua script nên khoá không bao giờ mất TTL.
 */
@Component
public class RecognitionRateLimiter {

  private static final Logger log = LoggerFactory.getLogger(RecognitionRateLimiter.class);
  static final int WINDOW_SECONDS = 60;

  @SuppressWarnings({"rawtypes", "unchecked"})
  private static final RedisScript<List<Long>> SCRIPT =
      (RedisScript)
          RedisScript.of(
              """
              local count = redis.call('INCR', KEYS[1])
              local ttl = redis.call('TTL', KEYS[1])
              if ttl < 0 then
                redis.call('EXPIRE', KEYS[1], ARGV[1])
                ttl = tonumber(ARGV[1])
              end
              return {count, ttl}
              """,
              List.class);

  private final StringRedisTemplate redis;

  public RecognitionRateLimiter(StringRedisTemplate redis) {
    this.redis = redis;
  }

  /**
   * Tính một lần gọi.
   *
   * @throws ApiException {@code 429 RATE_LIMITED} kèm {@code Retry-After} là số giây còn lại
   */
  public void acquire(RateLimit limit) {
    List<Long> result;
    try {
      result = redis.execute(SCRIPT, List.of(limit.key()), String.valueOf(WINDOW_SECONDS));
    } catch (DataAccessException e) {
      // Fail-open: nhận diện công khai, dịch vụ nhận diện tự giới hạn hàng chờ (503).
      log.warn("Không kiểm tra được giới hạn tần suất: {}", e.getClass().getSimpleName());
      return;
    }
    if (result == null || result.size() != 2) {
      log.warn("Script giới hạn tần suất trả kết quả lạ");
      return;
    }
    if (result.get(0) > limit.perMinute()) {
      throw ApiException.rateLimited(retryAfter(result.get(1)));
    }
  }

  static int retryAfter(long ttlSeconds) {
    return (int) Math.max(1, Math.min(ttlSeconds, WINDOW_SECONDS));
  }
}
