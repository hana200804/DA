package vn.signa.identity.internal;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.RedisOperations;
import org.springframework.data.redis.core.SessionCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * {@code session:{hash}} là hash {@code userId, role, client, createdAt} có TTL; {@code
 * user-sessions:{userId}} là set các mã phiên, không TTL, dọn mục hết hạn mỗi lần tạo phiên mới.
 */
@Component
class RedisSessionStore implements SessionStore {

  static final String SESSION_PREFIX = "session:";
  static final String USER_SESSIONS_PREFIX = "user-sessions:";

  private static final Logger log = LoggerFactory.getLogger(RedisSessionStore.class);

  private final StringRedisTemplate redis;

  RedisSessionStore(StringRedisTemplate redis) {
    this.redis = redis;
  }

  @Override
  public void create(String sessionId, Session session) {
    String key = SESSION_PREFIX + sessionId;
    String userSessions = USER_SESSIONS_PREFIX + session.userId();
    Map<String, String> fields =
        Map.of(
            "userId", session.userId(),
            "role", session.role(),
            "client", session.client().name(),
            "createdAt", session.createdAt().toString());
    redis.execute(
        new SessionCallback<List<Object>>() {
          @Override
          @SuppressWarnings("unchecked")
          public <K, V> List<Object> execute(RedisOperations<K, V> operations)
              throws DataAccessException {
            RedisOperations<String, String> ops = (RedisOperations<String, String>) operations;
            ops.multi();
            ops.opsForHash().putAll(key, fields);
            ops.expire(key, session.client().ttl());
            ops.opsForSet().add(userSessions, sessionId);
            return ops.exec();
          }
        });
    pruneExpired(userSessions, sessionId);
  }

  @Override
  public Optional<Session> findAndTouch(String sessionId) {
    String key = SESSION_PREFIX + sessionId;
    Map<Object, Object> fields = redis.opsForHash().entries(key);
    if (fields.isEmpty()) {
      return Optional.empty();
    }
    Session session;
    try {
      session =
          new Session(
              (String) fields.get("userId"),
              (String) fields.get("role"),
              Client.valueOf((String) fields.get("client")),
              Instant.parse((String) fields.get("createdAt")));
    } catch (RuntimeException e) {
      log.warn("Phiên hỏng trong Redis, xoá");
      redis.delete(key);
      return Optional.empty();
    }
    if (session.userId() == null || session.role() == null) {
      redis.delete(key);
      return Optional.empty();
    }
    // Phiên có thể vừa hết hạn giữa hai lệnh; expire trả false thì coi như không có.
    if (!Boolean.TRUE.equals(redis.expire(key, session.client().ttl()))) {
      return Optional.empty();
    }
    return Optional.of(session);
  }

  @Override
  public void delete(String sessionId) {
    String key = SESSION_PREFIX + sessionId;
    Object userId = redis.opsForHash().get(key, "userId");
    redis.delete(key);
    if (userId instanceof String id) {
      redis.opsForSet().remove(USER_SESSIONS_PREFIX + id, sessionId);
    }
  }

  @Override
  public void deleteAllOf(String userId, String keepSessionId) {
    String userSessions = USER_SESSIONS_PREFIX + userId;
    Set<String> members = redis.opsForSet().members(userSessions);
    if (members == null) {
      return;
    }
    for (String sessionId : members) {
      if (sessionId.equals(keepSessionId)) {
        continue;
      }
      redis.delete(SESSION_PREFIX + sessionId);
      redis.opsForSet().remove(userSessions, sessionId);
    }
  }

  private void pruneExpired(String userSessions, String current) {
    Set<String> members = redis.opsForSet().members(userSessions);
    if (members == null) {
      return;
    }
    for (String sessionId : members) {
      if (!sessionId.equals(current)
          && !Boolean.TRUE.equals(redis.hasKey(SESSION_PREFIX + sessionId))) {
        redis.opsForSet().remove(userSessions, sessionId);
      }
    }
  }
}
