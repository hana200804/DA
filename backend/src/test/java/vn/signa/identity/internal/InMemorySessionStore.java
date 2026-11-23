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
  public Optional<Session> findAndTouch(String sessionId) {
    Entry entry = sessions.get(sessionId);
    if (entry == null || !clock.instant().isBefore(entry.expiresAt())) {
      sessions.remove(sessionId);
      return Optional.empty();
    }
    Session session = entry.session();
    sessions.put(sessionId, new Entry(session, clock.instant().plus(session.client().ttl())));
    return Optional.of(session);
  }

  @Override
  public void delete(String sessionId) {
    sessions.remove(sessionId);
  }

  @Override
  public void deleteAllOf(String userId, String keepSessionId) {
    sessions
        .entrySet()
        .removeIf(e ->
            e.getValue().session().userId().equals(userId) && !e.getKey().equals(keepSessionId));
  }

  public int size() {
    return sessions.size();
  }
}
