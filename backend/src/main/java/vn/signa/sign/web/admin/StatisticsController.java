package vn.signa.sign.web.admin;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import vn.signa.shared.error.ApiException;
import vn.signa.shared.error.ErrorCode;
import vn.signa.shared.error.FieldIssue;
import vn.signa.sign.internal.stats.StatisticsService;
import vn.signa.sign.internal.stats.StatisticsView;

@RestController
class StatisticsController {

  private final StatisticsService statistics;

  StatisticsController(StatisticsService statistics) {
    this.statistics = statistics;
  }

  @GetMapping("/api/v1/admin/stats")
  @PreAuthorize("hasAuthority('stats:read')")
  StatisticsView statistics(
      @RequestParam(required = false) String from, @RequestParam(required = false) String to) {
    return statistics.statistics(parse("from", from), parse("to", to));
  }

  private static Instant parse(String field, String value) {
    if (value == null || value.isEmpty()) return null;
    try {
      return Instant.parse(value);
    } catch (DateTimeParseException e) {
      throw new ApiException(
          ErrorCode.VALIDATION_ERROR,
          ErrorCode.VALIDATION_ERROR.defaultMessage(),
          List.of(new FieldIssue(field, "phải là thời điểm ISO-8601 có độ lệch UTC")),
          null);
    }
  }
}
