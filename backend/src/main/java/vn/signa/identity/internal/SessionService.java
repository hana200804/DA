package vn.signa.identity.internal;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import vn.signa.identity.api.SessionRevoker;
import vn.signa.identity.internal.UserRepository.AuthState;

/** Cấp, tra và thu hồi phiên (FR-01, NFR-10). */
@Service
public class SessionService implements SessionRevoker {

  private static final Logger log = LoggerFactory.getLogger(SessionService.class);
  private static final int CLEANUP_ATTEMPTS = 3;

  /** Token trả cho client một lần duy nhất; server chỉ giữ {@code sessionId}. */
  public record IssuedSession(String token, String sessionId, Instant expiresAt) {}

  /** Phiên hợp lệ của request hiện tại. */
  public record ActiveSession(String sessionId, String userId, String role) {}

  private final SessionStore store;
  private final UserRepository users;
  private final Clock clock;

  SessionService(SessionStore store, UserRepository users, Clock clock) {
    this.store = store;
    this.users = users;
    this.clock = clock;
  }

  IssuedSession issue(String userId, int credentialVersion, Client client) {
    String token = SessionTokens.newToken();
    String sessionId = SessionTokens.hash(token);
    Instant now = clock.instant().truncatedTo(ChronoUnit.MILLIS);
    store.create(sessionId, new Session(userId, credentialVersion, client, now));
    return new IssuedSession(token, sessionId, now.plus(client.ttl()));
  }

  /** Tra trạng thái từ MySQL trên mỗi request trước khi gia hạn phiên. */
  public Optional<ActiveSession> authenticate(String token) {
    if (!SessionTokens.isWellFormed(token)) {
      return Optional.empty();
    }
    String sessionId = SessionTokens.hash(token);
    return store
        .find(sessionId)
        .flatMap(session ->
            users
                .findAuthState(session.userId())
                .filter(user ->
                    user.status() == UserStatus.ACTIVE && matchesVersion(user, session, sessionId))
                .filter(user -> store.touch(sessionId, session.client()))
                .map(user -> new ActiveSession(sessionId, session.userId(), user.role())));
  }

  private boolean matchesVersion(AuthState user, Session session, String sessionId) {
    if (user.credentialVersion() == session.credentialVersion()) {
      return true;
    }
    // Nếu Redis lỗi sau commit, chỉ phiên được MySQL giữ lại mới được chuyển sang phiên bản mới.
    if (sessionId.equals(user.retainedSession())
        && session.credentialVersion() == user.credentialVersion() - 1) {
      store.promote(session.userId(), sessionId, user.credentialVersion());
      return true;
    }
    return false;
  }

  public void revoke(String sessionId) {
    store.delete(sessionId);
  }

  /** Giữ phiên dùng để đổi mật khẩu, rồi dọn các phiên cũ (FR-01). */
  void keepAfterCommit(String userId, int credentialVersion, String sessionId) {
    afterCommit(
        userId,
        () -> {
          store.promote(userId, sessionId, credentialVersion);
          store.deleteOlder(userId, credentialVersion);
        });
  }

  @Override
  public void revokeOlderAfterCommit(String userId, int credentialVersion) {
    afterCommit(userId, () -> store.deleteOlder(userId, credentialVersion));
  }

  private static void afterCommit(String userId, Runnable cleanup) {
    TransactionSynchronizationManager.registerSynchronization(
        new TransactionSynchronization() {
          @Override
          public void afterCommit() {
            for (int attempt = 1; attempt <= CLEANUP_ATTEMPTS; attempt++) {
              try {
                cleanup.run();
                return;
              } catch (RuntimeException e) {
                if (attempt == CLEANUP_ATTEMPTS) {
                  log.warn(
                      "Không cập nhật được Redis sau khi đổi phiên bản xác thực userId={}", userId);
                }
              }
            }
          }
        });
  }
}
