package vn.signa.identity.internal;

import java.time.Duration;

/** Loại client khi đăng nhập, quyết định thời hạn phiên (docs/06 §10). */
public enum Client {
  MOBILE(Duration.ofDays(30)),
  WEB(Duration.ofHours(8));

  private final Duration ttl;

  Client(Duration ttl) {
    this.ttl = ttl;
  }

  /** Thời hạn phiên, tính lại từ lần dùng gần nhất. */
  public Duration ttl() {
    return ttl;
  }
}
