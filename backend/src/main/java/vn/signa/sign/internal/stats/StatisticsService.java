package vn.signa.sign.internal.stats;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import vn.signa.catalog.api.SignCatalog;
import vn.signa.sign.internal.map.MapSignRepository;
import vn.signa.sign.internal.stats.StatisticsView.CategoryCount;
import vn.signa.sign.internal.stats.StatisticsView.DailySubmissions;

@Service
public class StatisticsService {

  private static final int DENSITY_ZOOM = 12;

  private final StatisticsRepository statistics;
  private final MapSignRepository map;
  private final SignCatalog catalog;
  private final Clock clock;

  StatisticsService(
      StatisticsRepository statistics, MapSignRepository map, SignCatalog catalog, Clock clock) {
    this.statistics = statistics;
    this.map = map;
    this.catalog = catalog;
    this.clock = clock;
  }

  @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
  public StatisticsView statistics(Instant from, Instant to) {
    StatisticsRange range = StatisticsRange.resolve(from, to, clock);
    var submissions = fillDays(range, statistics.submissions(range));
    var decision = statistics.decisions(range);
    Map<String, String> names = catalog.categoryNames();
    List<CategoryCount> categories =
        statistics.categories().stream()
            .map(
                c -> new CategoryCount(c.code(), names.getOrDefault(c.code(), c.code()), c.count()))
            .toList();
    return new StatisticsView(
        submissions,
        decision.decisions(),
        decision.processingTime(),
        categories,
        map.countApprovedByCell(DENSITY_ZOOM));
  }

  static List<DailySubmissions> fillDays(StatisticsRange range, List<DailySubmissions> counts) {
    Map<LocalDate, Long> byDay =
        counts.stream().collect(Collectors.toMap(DailySubmissions::date, DailySubmissions::count));
    LocalDate start = LocalDate.ofInstant(range.from(), ZoneOffset.UTC);
    LocalDate end = LocalDate.ofInstant(range.to(), ZoneOffset.UTC);
    return start
        .datesUntil(end.plusDays(1))
        .map(day -> new DailySubmissions(day, byDay.getOrDefault(day, 0L)))
        .toList();
  }
}
