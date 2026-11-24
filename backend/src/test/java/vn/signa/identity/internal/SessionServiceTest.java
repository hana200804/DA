package vn.signa.identity.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import vn.signa.identity.internal.SessionService.IssuedSession;

class SessionServiceTest {

  private final MutableClock clock = new MutableClock(Instant.parse("2026-11-23T08:00:00.123456Z"));
  private final InMemorySessionStore store = new InMemorySessionStore(clock);
  private final SessionService sessions = new SessionService(store, clock);

  @Test
  void issuedSessionAuthenticatesAndStoresOnlyTheHash() {
    IssuedSession issued = sessions.issue("u-1", "USER", Client.MOBILE);

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
    IssuedSession issued = sessions.issue("u-1", "MODERATOR", Client.WEB);
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
    IssuedSession a = sessions.issue("u-1", "USER", Client.MOBILE);
    IssuedSession b = sessions.issue("u-1", "USER", Client.WEB);

    sessions.revoke(a.sessionId());

    assertThat(sessions.authenticate(a.token())).isEmpty();
    assertThat(sessions.authenticate(b.token())).isPresent();
  }

  @Test
  void revokeOthersKeepsCurrentSession() {
    IssuedSession current = sessions.issue("u-1", "USER", Client.MOBILE);
    IssuedSession other = sessions.issue("u-1", "USER", Client.MOBILE);
    IssuedSession someoneElse = sessions.issue("u-2", "USER", Client.MOBILE);

    sessions.revokeOthers("u-1", current.sessionId());

    assertThat(sessions.authenticate(current.token())).isPresent();
    assertThat(sessions.authenticate(other.token())).isEmpty();
    assertThat(sessions.authenticate(someoneElse.token())).isPresent();
  }

  @Test
  void revokeAllRemovesEverySessionOfUser() {
    IssuedSession a = sessions.issue("u-1", "USER", Client.MOBILE);
    IssuedSession b = sessions.issue("u-1", "USER", Client.WEB);

    sessions.revokeAll("u-1");

    assertThat(sessions.authenticate(a.token())).isEmpty();
    assertThat(sessions.authenticate(b.token())).isEmpty();
    assertThat(store.size()).isZero();
  }
}
