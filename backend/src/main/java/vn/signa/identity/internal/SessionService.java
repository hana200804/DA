package vn.signa.identity.internal;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import org.springframework.stereotype.Service;
import vn.signa.identity.api.SessionRevoker;

/** Cấp, tra và thu hồi phiên (FR-01, NFR-10). */
@Service
public class SessionService implements SessionRevoker {

  /** Token trả cho client một lần duy nhất; server chỉ giữ {@code sessionId}. */
  public record IssuedSession(String token, String sessionId, Instant expiresAt) {}

  /** Phiên hợp lệ của request hiện tại. */
  public record ActiveSession(String sessionId, String userId, String role) {}

  private final SessionStore store;
  private final Clock clock;

  SessionService(SessionStore store, Clock clock) {
    this.store = store;
    this.clock = clock;
  }

  public IssuedSession issue(String userId, String role, Client client) {
    String token = SessionTokens.newToken();
    String sessionId = SessionTokens.hash(token);
    Instant now = clock.instant().truncatedTo(ChronoUnit.MILLIS);
    store.create(sessionId, new Session(userId, role, client, now));
    return new IssuedSession(token, sessionId, now.plus(client.ttl()));
  }

  /** Tra phiên theo token và gia hạn; token sai định dạng không chạm tới Redis. */
  public Optional<ActiveSession> authenticate(String token) {
    if (!SessionTokens.isWellFormed(token)) {
      return Optional.empty();
    }
    String sessionId = SessionTokens.hash(token);
    return store
        .findAndTouch(sessionId)
        .map(s -> new ActiveSession(sessionId, s.userId(), s.role()));
  }

  public void revoke(String sessionId) {
    store.delete(sessionId);
  }

  /** Giữ phiên đang dùng, xoá các phiên khác (đổi mật khẩu, FR-01). */
  public void revokeOthers(String userId, String keepSessionId) {
    store.deleteAllOf(userId, keepSessionId);
  }

  @Override
  public void revokeAll(String userId) {
    store.deleteAllOf(userId, null);
  }
}
