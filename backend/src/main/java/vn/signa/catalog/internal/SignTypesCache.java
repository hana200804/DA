package vn.signa.catalog.internal;

import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/** Cache theo phiên bản đã commit trong MySQL. ETag được tính từ body. */
@Component
public class SignTypesCache {

  private static final String KEY_PREFIX = "cache:sign-types:";
  private static final Logger log = LoggerFactory.getLogger(SignTypesCache.class);

  private final StringRedisTemplate redis;
  private final CatalogProperties properties;

  SignTypesCache(StringRedisTemplate redis, CatalogProperties properties) {
    this.redis = redis;
    this.properties = properties;
  }

  static String key(long generation) {
    return KEY_PREFIX + generation;
  }

  Optional<String> get(long generation) {
    String key = key(generation);
    try {
      return Optional.ofNullable(redis.opsForValue().get(key));
    } catch (DataAccessException e) {
      log.warn("Không đọc được {} từ Redis, đọc MySQL", key, e);
      return Optional.empty();
    }
  }

  void put(long generation, String json) {
    String key = key(generation);
    try {
      redis.opsForValue().set(key, json, properties.signTypesCacheTtl());
    } catch (DataAccessException e) {
      log.warn("Không ghi được {} vào Redis", key, e);
    }
  }
}
