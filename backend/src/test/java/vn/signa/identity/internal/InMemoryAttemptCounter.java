package vn.signa.identity.internal;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/** Bộ đếm cửa sổ cố định trong bộ nhớ theo {@link Clock}, thay Redis trong test unit. */
public class InMemoryAttemptCounter implements AttemptCounter {

  private record Entry(long count, Instant expiresAt) {}

  private final Map<String, Entry> entries = new HashMap<>();
  private final Clock clock;

  public InMemoryAttemptCounter(Clock clock) {
    this.clock = clock;
  }

  private Entry live(String key) {
    Entry e = entries.get(key);
    if (e != null && !clock.instant().isBefore(e.expiresAt())) {
      entries.remove(key);
      return null;
    }
    return e;
  }

  @Override
  public long get(String key) {
    Entry e = live(key);
    return e == null ? 0 : e.count();
  }

  @Override
  public long increment(String key, Duration window) {
    Entry e = live(key);
    Entry next =
        e == null
            ? new Entry(1, clock.instant().plus(window))
            : new Entry(e.count() + 1, e.expiresAt());
    entries.put(key, next);
    return next.count();
  }

  @Override
  public Duration remaining(String key) {
    Entry e = live(key);
    return e == null ? Duration.ZERO : Duration.between(clock.instant(), e.expiresAt());
  }

  @Override
  public void reset(String key) {
    entries.remove(key);
  }
}
