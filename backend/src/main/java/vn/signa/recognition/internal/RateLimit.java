package vn.signa.recognition.internal;

import java.util.Optional;

/** Khoá Redis và số lần cho phép mỗi phút của một người gọi (docs/07 §5, docs/06 §10). */
public record RateLimit(String key, int perMinute) {

  static final String USER_PREFIX = "rl:recognize:user:";
  static final String IP_PREFIX = "rl:recognize:ip:";

  /** Người dùng đăng nhập tính theo tài khoản, khách tính theo IP. */
  public static RateLimit forCaller(
      Optional<String> userId, String ip, int guestPerMinute, int userPerMinute) {
    return userId
        .map(id -> new RateLimit(USER_PREFIX + id, userPerMinute))
        .orElseGet(() -> new RateLimit(IP_PREFIX + ip, guestPerMinute));
  }
}
