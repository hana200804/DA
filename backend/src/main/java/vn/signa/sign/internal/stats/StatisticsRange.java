package vn.signa.sign.internal.stats;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import vn.signa.shared.error.ApiException;
import vn.signa.shared.error.ErrorCode;
import vn.signa.shared.error.FieldIssue;

/** Hai đầu mút đều thuộc khoảng thời gian. */
record StatisticsRange(Instant from, Instant to) {

  private static final Duration DEFAULT_RANGE = Duration.ofDays(30);
  private static final Duration MAX_RANGE = Duration.ofDays(366);
  private static final Instant MIN_DATETIME = Instant.parse("1000-01-01T00:00:00Z");
  private static final Instant MAX_DATETIME = Instant.parse("9999-12-31T23:59:59.999Z");

  static StatisticsRange resolve(Instant from, Instant to, Clock clock) {
    Instant end = to == null ? clock.instant() : to;
    validateDateTime("to", end);
    Instant start = from == null ? end.minus(DEFAULT_RANGE) : from;
    validateDateTime("from", start);
    if (start.isAfter(end)) {
      throw invalid("from", "phải trước hoặc bằng to");
    }
    if (Duration.between(start, end).compareTo(MAX_RANGE) > 0) {
      throw invalid("from", "khoảng từ from đến to tối đa 366 ngày");
    }
    return new StatisticsRange(start, end);
  }

  private static void validateDateTime(String field, Instant at) {
    if (at.isBefore(MIN_DATETIME) || at.isAfter(MAX_DATETIME)) {
      throw invalid(field, "thời điểm theo UTC ngoài phạm vi DATETIME(3)");
    }
  }

  private static ApiException invalid(String field, String issue) {
    return new ApiException(
        ErrorCode.VALIDATION_ERROR,
        ErrorCode.VALIDATION_ERROR.defaultMessage(),
        List.of(new FieldIssue(field, issue)),
        null);
  }
}
