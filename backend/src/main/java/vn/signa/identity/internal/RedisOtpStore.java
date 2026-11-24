package vn.signa.identity.internal;

import java.time.Duration;
import java.util.List;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

@Component
class RedisOtpStore {

  static final Duration OTP_TTL = Duration.ofMinutes(10);
  static final Duration SEND_WINDOW = Duration.ofMinutes(15);
  static final int MAX_SENDS = 3;

  private static final DefaultRedisScript<Long> RESERVE_SEND =
      new DefaultRedisScript<>(
          """
          local count = redis.call('INCR', KEYS[1])
          if count == 1 then
            redis.call('PEXPIRE', KEYS[1], ARGV[1])
          end
          if count <= tonumber(ARGV[2]) then return 0 end
          return math.max(1, math.ceil(redis.call('PTTL', KEYS[1]) / 1000))
          """,
          Long.class);

  private static final DefaultRedisScript<Long> ISSUE =
      new DefaultRedisScript<>(
          """
          if redis.call('HGET', KEYS[1], 'codeHash') == ARGV[1] then return 0 end
          redis.call('HSET', KEYS[1], 'codeHash', ARGV[1], 'attempts', 0)
          redis.call('PEXPIRE', KEYS[1], ARGV[2])
          return 1
          """,
          Long.class);

  private static final DefaultRedisScript<Long> APPLY_ATTEMPT =
      new DefaultRedisScript<>(
          """
          if redis.call('HGET', KEYS[1], 'codeHash') ~= ARGV[1]
              or redis.call('HGET', KEYS[1], 'attempts') ~= ARGV[2] then
            return 0
          end
          if ARGV[3] == 'delete' then
            redis.call('DEL', KEYS[1])
          else
            redis.call('HINCRBY', KEYS[1], 'attempts', 1)
          end
          return 1
          """,
          Long.class);

  record Challenge(String codeHash, int attempts) {}

  private final StringRedisTemplate redis;

  RedisOtpStore(StringRedisTemplate redis) {
    this.redis = redis;
  }

  long reserveSend(String emailHash, String ip) {
    String key = "rl:otp:" + emailHash + ":" + ip;
    Long retryAfter =
        redis.execute(
            RESERVE_SEND,
            List.of(key),
            Long.toString(SEND_WINDOW.toMillis()),
            Integer.toString(MAX_SENDS));
    if (retryAfter == null) {
      throw new IllegalStateException("Không nhận được kết quả giới hạn gửi OTP");
    }
    return retryAfter;
  }

  boolean issue(String emailHash, String codeHash) {
    return Long.valueOf(1)
        .equals(
            redis.execute(
                ISSUE, List.of(otpKey(emailHash)), codeHash, Long.toString(OTP_TTL.toMillis())));
  }

  Challenge find(String emailHash) {
    HashOperations<String, String, String> hash = redis.opsForHash();
    List<String> values = hash.multiGet(otpKey(emailHash), List.of("codeHash", "attempts"));
    String codeHash = values.getFirst();
    return codeHash == null ? null : new Challenge(codeHash, Integer.parseInt(values.get(1)));
  }

  /** Cập nhật nếu mã và bộ đếm còn khớp với lần đọc, giữ nguyên TTL khi tăng. */
  boolean applyAttempt(String emailHash, Challenge challenge, boolean delete) {
    return Long.valueOf(1)
        .equals(
            redis.execute(
                APPLY_ATTEMPT,
                List.of(otpKey(emailHash)),
                challenge.codeHash(),
                Integer.toString(challenge.attempts()),
                delete ? "delete" : "increment"));
  }

  private static String otpKey(String emailHash) {
    return "otp:" + emailHash;
  }
}
