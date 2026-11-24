package vn.signa.identity.internal;

import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/** Kho phiên trong bộ nhớ có TTL theo {@link Clock}, thay Redis trong test unit. */
public class InMemorySessionStore implements SessionStore {

  private record Entry(Session session, Instant expiresAt) {}

  private final Map<String, Entry> sessions = new HashMap<>();
  private final Clock clock;

  public InMemorySessionStore(Clock clock) {
    this.clock = clock;
  }

  @Override
  public void create(String sessionId, Session session) {
    sessions.put(sessionId, new Entry(session, clock.instant().plus(session.client().ttl())));
  }

  @Override
  public Optional<Session> find(String sessionId) {
    Entry entry = sessions.get(sessionId);
    if (entry == null || !clock.instant().isBefore(entry.expiresAt())) {
      sessions.remove(sessionId);
      return Optional.empty();
    }
    return Optional.of(entry.session());
  }

  @Override
  public boolean touch(String sessionId, Client client) {
    return find(sessionId)
        .map(session -> {
          sessions.put(sessionId, new Entry(session, clock.instant().plus(client.ttl())));
          return true;
        })
        .orElse(false);
  }

  @Override
  public void delete(String sessionId) {
    sessions.remove(sessionId);
  }

  @Override
  public void promote(String userId, String sessionId, int credentialVersion) {
    find(sessionId)
        .filter(session ->
            session.userId().equals(userId) && session.credentialVersion() == credentialVersion - 1)
        .ifPresent(session ->
            sessions.put(
                sessionId,
                new Entry(
                    new Session(userId, credentialVersion, session.client(), session.createdAt()),
                    sessions.get(sessionId).expiresAt())));
  }

  @Override
  public void deleteOlder(String userId, int credentialVersion) {
    sessions
        .values()
        .removeIf(entry ->
            entry.session().userId().equals(userId)
                && entry.session().credentialVersion() < credentialVersion);
  }

  public int size() {
    return sessions.size();
  }
}
