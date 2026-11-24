package vn.signa.sign.internal.stats;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import vn.signa.sign.internal.stats.StatisticsView.DailySubmissions;
import vn.signa.sign.internal.stats.StatisticsView.Decisions;
import vn.signa.sign.internal.stats.StatisticsView.ProcessingTime;

@Repository
class StatisticsRepository {

  private final JdbcClient jdbc;

  StatisticsRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  List<DailySubmissions> submissions(StatisticsRange range) {
    return within(
            """
            SELECT DATE(received_at) AS day, COUNT(*) AS n
            FROM submissions
            WHERE received_at BETWEEN :from AND :to
            GROUP BY DATE(received_at)
            """,
            range)
        .query((rs, row) ->
            new DailySubmissions(rs.getObject("day", LocalDate.class), rs.getLong("n")))
        .list();
  }

  DecisionSummary decisions(StatisticsRange range) {
    return within(
            """
            SELECT COUNT(CASE WHEN status = 'APPROVED' THEN 1 END) AS approved,
                   COUNT(CASE WHEN status = 'REJECTED' THEN 1 END) AS rejected,
                   AVG(elapsed_ms) AS average_ms, COUNT(elapsed_ms) AS samples
            FROM (
              SELECT s.status, TIMESTAMPDIFF(MICROSECOND, MIN(p.received_at), s.reviewed_at)
                       DIV 1000 AS elapsed_ms
              FROM signs s
              LEFT JOIN submissions p ON p.sign_id = s.id AND p.received_at <= s.reviewed_at
              WHERE s.status IN ('APPROVED', 'REJECTED') AND s.reviewed_at BETWEEN :from AND :to
              GROUP BY s.id, s.status, s.reviewed_at
            ) AS decided
            """,
            range)
        .query((rs, row) ->
            new DecisionSummary(
                new Decisions(rs.getLong("approved"), rs.getLong("rejected")),
                new ProcessingTime(rs.getBigDecimal("average_ms"), rs.getLong("samples"))))
        .single();
  }

  List<CategoryTotal> categories() {
    return jdbc.sql(
            """
            SELECT category_code, COUNT(*) AS n FROM signs
            WHERE status = 'APPROVED'
            GROUP BY category_code ORDER BY category_code
            """)
        .query((rs, row) -> new CategoryTotal(rs.getString("category_code"), rs.getLong("n")))
        .list();
  }

  private JdbcClient.StatementSpec within(String sql, StatisticsRange range) {
    // Các cột lưu DATETIME(3) theo UTC. Làm tròn hướng vào trong để giữ đúng hai đầu mút.
    Instant start = range.from().truncatedTo(ChronoUnit.MILLIS);
    if (start.isBefore(range.from())) start = start.plusMillis(1);
    return jdbc.sql(sql)
        .param("from", LocalDateTime.ofInstant(start, ZoneOffset.UTC))
        .param(
            "to",
            LocalDateTime.ofInstant(range.to().truncatedTo(ChronoUnit.MILLIS), ZoneOffset.UTC));
  }

  record DecisionSummary(Decisions decisions, ProcessingTime processingTime) {}

  record CategoryTotal(String code, long count) {}
}
