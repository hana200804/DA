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
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

/**
 * {@code session:{hash}} là hash {@code userId, credentialVersion, client, createdAt} có TTL;
 * {@code user-sessions:{userId}} là set các mã phiên, không TTL, dọn mục hết hạn mỗi lần tạo phiên
 * mới.
 */
@Component
class RedisSessionStore implements SessionStore {

  static final String SESSION_PREFIX = "session:";
  static final String USER_SESSIONS_PREFIX = "user-sessions:";

  private static final RedisScript<Long> PROMOTE =
      RedisScript.of(
          """
          local user = redis.call('HGET', KEYS[1], 'userId')
          local version = tonumber(redis.call('HGET', KEYS[1], 'credentialVersion') or '')
          if user == ARGV[1] and version == tonumber(ARGV[2]) - 1 then
            return redis.call('HSET', KEYS[1], 'credentialVersion', ARGV[2])
          end
          return 0
          """,
          Long.class);

  private static final RedisScript<Long> DELETE_OLDER =
      RedisScript.of(
          """
          local user = redis.call('HGET', KEYS[1], 'userId')
          local version = tonumber(redis.call('HGET', KEYS[1], 'credentialVersion') or '')
          if user == ARGV[1] and (not version or version < tonumber(ARGV[2])) then
            redis.call('DEL', KEYS[1])
            user = false
          end
          if not user then
            return redis.call('SREM', KEYS[2], ARGV[3])
          end
          return 0
          """,
          Long.class);

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
            "credentialVersion", Integer.toString(session.credentialVersion()),
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
  public Optional<Session> find(String sessionId) {
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
              Integer.parseInt((String) fields.get("credentialVersion")),
              Client.valueOf((String) fields.get("client")),
              Instant.parse((String) fields.get("createdAt")));
    } catch (RuntimeException e) {
      log.warn("Phiên hỏng trong Redis, xoá");
      redis.delete(key);
      return Optional.empty();
    }
    if (session.userId() == null || session.credentialVersion() < 0) {
      redis.delete(key);
      return Optional.empty();
    }
    return Optional.of(session);
  }

  @Override
  public boolean touch(String sessionId, Client client) {
    return Boolean.TRUE.equals(redis.expire(SESSION_PREFIX + sessionId, client.ttl()));
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
  public void promote(String userId, String sessionId, int credentialVersion) {
    redis.execute(
        PROMOTE, List.of(SESSION_PREFIX + sessionId), userId, Integer.toString(credentialVersion));
  }

  @Override
  public void deleteOlder(String userId, int credentialVersion) {
    String userSessions = USER_SESSIONS_PREFIX + userId;
    Set<String> members = redis.opsForSet().members(userSessions);
    if (members == null) {
      return;
    }
    for (String sessionId : members) {
      redis.execute(
          DELETE_OLDER,
          List.of(SESSION_PREFIX + sessionId, userSessions),
          userId,
          Integer.toString(credentialVersion),
          sessionId);
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
