package vn.signa.sign.internal.stats;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import vn.signa.sign.internal.map.MapResult.MapCluster;

/** Thống kê quản trị (FR-38). */
public record StatisticsView(
    List<DailySubmissions> submissionsByDay,
    Decisions decisions,
    ProcessingTime processingTime,
    List<CategoryCount> signsByCategory,
    List<MapCluster> densityCells) {

  public record DailySubmissions(LocalDate date, long count) {}

  public record Decisions(long approved, long rejected) {}

  public record ProcessingTime(BigDecimal averageMs, long samples) {}

  public record CategoryCount(String categoryCode, String name, long count) {}
}
