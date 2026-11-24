package vn.signa.identity.internal;

import java.time.Duration;

/** Bộ đếm cửa sổ cố định. Cài đặt thật là {@code INCR} cùng {@code EXPIRE} trong Redis. */
interface AttemptCounter {

  long get(String key);

  /** Tăng một; lần đầu trong cửa sổ thì đặt thời hạn {@code window}. */
  long increment(String key, Duration window);

  /** Thời gian còn lại của cửa sổ; {@link Duration#ZERO} nếu khoá không còn. */
  Duration remaining(String key);

  void reset(String key);
}
