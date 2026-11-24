package vn.signa.identity.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import vn.signa.identity.internal.SessionService.IssuedSession;
import vn.signa.identity.internal.UserRepository.AuthState;

class SessionServiceTest {

  private final MutableClock clock = new MutableClock(Instant.parse("2026-11-23T08:00:00.123456Z"));
  private final InMemorySessionStore store = new InMemorySessionStore(clock);
  private final UserRepository users = mock(UserRepository.class);
  private final SessionService sessions = new SessionService(store, users, clock);

  @BeforeEach
  void activeUser() {
    authState("USER", UserStatus.ACTIVE, 0, null);
  }

  private void authState(String role, UserStatus status, int version, String kept) {
    when(users.findAuthState("u-1"))
        .thenReturn(Optional.of(new AuthState(role, status, version, kept)));
  }

  @Test
  void issuedSessionAuthenticatesAndStoresOnlyTheHash() {
    IssuedSession issued = sessions.issue("u-1", 0, Client.MOBILE);

    assertThat(issued.sessionId()).isEqualTo(SessionTokens.hash(issued.token()));
    assertThat(issued.expiresAt()).isEqualTo(Instant.parse("2026-12-23T08:00:00.123Z"));
    assertThat(sessions.authenticate(issued.token()))
        .hasValueSatisfying(a -> {
          assertThat(a.userId()).isEqualTo("u-1");
          assertThat(a.role()).isEqualTo("USER");
          assertThat(a.sessionId()).isEqualTo(issued.sessionId());
        });
  }

  @Test
  void webSessionLastsEightHoursAndSlidesOnUse() {
    IssuedSession issued = sessions.issue("u-1", 0, Client.WEB);
    assertThat(issued.expiresAt()).isEqualTo(Instant.parse("2026-11-23T16:00:00.123Z"));

    clock.advance(Duration.ofHours(7));
    assertThat(sessions.authenticate(issued.token())).isPresent();
    clock.advance(Duration.ofHours(7));
    assertThat(sessions.authenticate(issued.token())).isPresent();
    clock.advance(Duration.ofHours(8).plusSeconds(1));
    assertThat(sessions.authenticate(issued.token())).isEmpty();
  }

  @Test
  void unknownOrMalformedTokenIsRejected() {
    assertThat(sessions.authenticate(SessionTokens.newToken())).isEmpty();
    assertThat(sessions.authenticate("không-phải-token")).isEmpty();
    assertThat(sessions.authenticate(null)).isEmpty();
  }

  @Test
  void revokeRemovesOnlyThatSession() {
    IssuedSession a = sessions.issue("u-1", 0, Client.MOBILE);
    IssuedSession b = sessions.issue("u-1", 0, Client.WEB);

    sessions.revoke(a.sessionId());

    assertThat(sessions.authenticate(a.token())).isEmpty();
    assertThat(sessions.authenticate(b.token())).isPresent();
  }

  @Test
  void currentStateControlsSessionEvenWhileRedisStillHasItsKey() {
    IssuedSession issued = sessions.issue("u-1", 0, Client.MOBILE);

    authState("MODERATOR", UserStatus.ACTIVE, 0, null);
    assertThat(sessions.authenticate(issued.token()))
        .get()
        .extracting(SessionService.ActiveSession::role)
        .isEqualTo("MODERATOR");

    authState("MODERATOR", UserStatus.LOCKED, 0, null);
    assertThat(sessions.authenticate(issued.token())).isEmpty();

    authState("MODERATOR", UserStatus.ACTIVE, 1, null);
    assertThat(sessions.authenticate(issued.token())).isEmpty();
    assertThat(store.find(issued.sessionId())).isPresent();
  }

  @Test
  void restoresOnlyTheSessionRetainedByMysqlWhenPostCommitPromotionFailed() {
    IssuedSession current = sessions.issue("u-1", 0, Client.MOBILE);
    IssuedSession other = sessions.issue("u-1", 0, Client.MOBILE);
    authState("USER", UserStatus.ACTIVE, 1, current.sessionId());

    assertThat(sessions.authenticate(current.token())).isPresent();
    assertThat(store.find(current.sessionId()))
        .get()
        .extracting(Session::credentialVersion)
        .isEqualTo(1);
    assertThat(sessions.authenticate(other.token())).isEmpty();
  }

  @Test
  void cleanupWaitsForCommitAndLeavesSessionsIssuedAtTheNewVersion() {
    IssuedSession stale = sessions.issue("u-1", 0, Client.MOBILE);
    IssuedSession fresh = sessions.issue("u-1", 1, Client.WEB);

    TransactionSynchronizationManager.initSynchronization();
    try {
      sessions.revokeOlderAfterCommit("u-1", 1);
      assertThat(store.size()).isEqualTo(2);
      TransactionSynchronizationManager.getSynchronizations()
          .forEach(TransactionSynchronization::afterCommit);
    } finally {
      TransactionSynchronizationManager.clearSynchronization();
    }
    assertThat(store.find(stale.sessionId())).isEmpty();
    assertThat(store.find(fresh.sessionId())).isPresent();
  }

  @Test
  void cleanupRetriesRedisErrorsAfterCommit() {
    SessionStore unavailable = mock(SessionStore.class);
    SessionService retrying = new SessionService(unavailable, users, clock);
    doThrow(new QueryTimeoutException("redis")).doNothing().when(unavailable).deleteOlder("u-1", 1);

    TransactionSynchronizationManager.initSynchronization();
    try {
      retrying.revokeOlderAfterCommit("u-1", 1);
      verifyNoInteractions(unavailable);
      TransactionSynchronizationManager.getSynchronizations()
          .forEach(TransactionSynchronization::afterCommit);
    } finally {
      TransactionSynchronizationManager.clearSynchronization();
    }
    verify(unavailable, times(2)).deleteOlder("u-1", 1);
  }
}
