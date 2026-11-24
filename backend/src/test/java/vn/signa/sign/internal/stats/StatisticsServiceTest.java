package vn.signa.sign.internal.stats;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;
import vn.signa.shared.error.ApiException;
import vn.signa.shared.error.ErrorCode;
import vn.signa.sign.internal.stats.StatisticsView.DailySubmissions;

class StatisticsServiceTest {

  private static final Instant NOW = Instant.parse("2026-11-24T12:00:00Z");
  private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

  @Test
  void fillsEveryUtcDayIncludingBothEnds() {
    var range =
        new StatisticsRange(
            Instant.parse("2026-01-15T23:59:59Z"), Instant.parse("2026-01-19T00:00:00Z"));

    assertThat(StatisticsService.fillDays(range, List.of(day(18, 2), day(16, 7))))
        .containsExactly(day(15, 0), day(16, 7), day(17, 0), day(18, 2), day(19, 0));
  }

  @Test
  void defaultsToThirtyDaysEndingAtToOrNow() {
    Instant explicitTo = NOW.minus(Duration.ofDays(1));
    assertThat(StatisticsRange.resolve(null, null, CLOCK))
        .isEqualTo(new StatisticsRange(NOW.minus(Duration.ofDays(30)), NOW));
    assertThat(StatisticsRange.resolve(null, explicitTo, CLOCK))
        .isEqualTo(new StatisticsRange(explicitTo.minus(Duration.ofDays(30)), explicitTo));
    assertThat(StatisticsRange.resolve(explicitTo, null, CLOCK))
        .isEqualTo(new StatisticsRange(explicitTo, NOW));
  }

  @Test
  void acceptsEqualEndpointsAndAFull366Days() {
    Instant from = NOW.minus(Duration.ofDays(366));
    assertThat(StatisticsRange.resolve(from, NOW, CLOCK).from()).isEqualTo(from);
    assertThat(StatisticsRange.resolve(NOW, NOW, CLOCK).to()).isEqualTo(NOW);
  }

  @Test
  void rejectsLongReversedOrUnrepresentableRanges() {
    assertInvalid(NOW.minus(Duration.ofDays(366)).minusNanos(1), NOW);
    assertInvalid(NOW, NOW.minusSeconds(1));
    assertInvalid(null, Instant.MIN);
    assertInvalid(NOW, Instant.MAX);
  }

  private static void assertInvalid(Instant from, Instant to) {
    assertThatThrownBy(() -> StatisticsRange.resolve(from, to, CLOCK))
        .isInstanceOfSatisfying(
            ApiException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.VALIDATION_ERROR));
  }

  private static DailySubmissions day(int day, long count) {
    return new DailySubmissions(LocalDate.of(2026, 1, day), count);
  }
}
