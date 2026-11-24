package vn.signa.sign.internal.places;

import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;
import vn.signa.shared.error.ApiException;

@Component
public class PlacesRateLimiter {

  private static final Logger log = LoggerFactory.getLogger(PlacesRateLimiter.class);
  private static final int WINDOW_SECONDS = 60;
  private static final int LIMIT = 30;

  @SuppressWarnings({"rawtypes", "unchecked"})
  private static final RedisScript<List<Long>> SCRIPT =
      (RedisScript) RedisScript.of(new ClassPathResource("redis/fixed-window.lua"), List.class);

  private final StringRedisTemplate redis;

  public PlacesRateLimiter(StringRedisTemplate redis) {
    this.redis = redis;
  }

  public void acquire(String ip) {
    List<Long> result;
    try {
      result = redis.execute(SCRIPT, List.of("rl:places:ip:" + ip), String.valueOf(WINDOW_SECONDS));
    } catch (DataAccessException e) {
      log.warn("Không kiểm tra được giới hạn tìm địa điểm: {}", e.getClass().getSimpleName());
      return;
    }
    if (result == null || result.size() != 2) {
      log.warn("Script giới hạn tìm địa điểm trả kết quả lạ");
      return;
    }
    if (result.getFirst() > LIMIT) {
      throw ApiException.rateLimited(Math.clamp(result.get(1), 1, WINDOW_SECONDS));
    }
  }
}
