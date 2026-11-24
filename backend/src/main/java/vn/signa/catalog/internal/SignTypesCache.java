package vn.signa.catalog.internal;

import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Body JSON của {@code GET /sign-types} trong Redis {@code cache:sign-types} (docs/06 §10). ETag
 * tính lại từ body nên không cần lưu riêng. Redis lỗi thì coi như không có cache: API đọc MySQL.
 */
@Component
public class SignTypesCache {

  static final String KEY = "cache:sign-types";

  private static final Logger log = LoggerFactory.getLogger(SignTypesCache.class);

  private final StringRedisTemplate redis;
  private final CatalogProperties properties;

  SignTypesCache(StringRedisTemplate redis, CatalogProperties properties) {
    this.redis = redis;
    this.properties = properties;
  }

  Optional<String> get() {
    try {
      return Optional.ofNullable(redis.opsForValue().get(KEY));
    } catch (DataAccessException e) {
      log.warn("Không đọc được {} từ Redis, đọc MySQL", KEY, e);
      return Optional.empty();
    }
  }

  void put(String json) {
    try {
      redis.opsForValue().set(KEY, json, properties.signTypesCacheTtl());
    } catch (DataAccessException e) {
      log.warn("Không ghi được {} vào Redis", KEY, e);
    }
  }

  void evict() {
    try {
      redis.delete(KEY);
    } catch (DataAccessException e) {
      log.warn(
          "Không xoá được {}; cache tự hết hạn sau {}", KEY, properties.signTypesCacheTtl(), e);
    }
  }

  /** Xoá cache sau khi giao dịch hiện tại commit, hoặc ngay nếu không có giao dịch. */
  public void evictAfterCommit() {
    if (!TransactionSynchronizationManager.isSynchronizationActive()) {
      evict();
      return;
    }
    TransactionSynchronizationManager.registerSynchronization(
        new TransactionSynchronization() {
          @Override
          public void afterCommit() {
            evict();
          }
        });
  }
}
