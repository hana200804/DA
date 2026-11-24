package vn.signa.sign.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static vn.signa.sign.internal.ReviewItSupport.ADMIN;

import java.time.Instant;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.json.JsonMapper;
import vn.signa.sign.clustering.GeoPoint;
import vn.signa.support.IntegrationTest;

class StatisticsIT extends IntegrationTest {

  @LocalServerPort int port;
  @Autowired JsonMapper json;
  @Autowired JdbcTemplate jdbc;

  @Test
  void aggregatesSeededRowsWithUtcDatesAndOnlyApprovedMapSigns() throws Exception {
    var it = new ReviewItSupport(port, json, jdbc);
    var admin = it.moderator();
    String category = it.category("ST");
    String code = it.type("A", category);
    // Nằm trong cùng ô zoom 12 và ngoài vùng fixture của các IT bản đồ/phê duyệt.
    GeoPoint at = new GeoPoint(78.12345, -169.112345);

    String approved =
        it.sign(code, category, at, "APPROVED", "0.9", null, Instant.parse("2015-01-15T13:00:00Z"));
    String rejected =
        it.sign(code, category, at, "REJECTED", "0.2", null, Instant.parse("2015-01-18T01:00:00Z"));
    it.sign(
        code,
        category,
        at,
        "APPROVED",
        "0.9",
        null,
        Instant.parse("2015-01-16T08:00:00Z")); // Thêm tay, không có đề xuất trước quyết định.
    it.sign(
        code,
        category,
        at,
        "APPROVED",
        "0.9",
        null,
        Instant.parse("2015-01-18T02:00:00Z")); // Ngoài khoảng quyết định, vẫn có trên bản đồ.
    it.sign(code, category, at, "PENDING", "0.6", "WAIT", Instant.parse("2015-01-16T08:00:00Z"));
    it.sign(
        code,
        category,
        at,
        "UNDER_REVIEW",
        "0.4",
        "INSPECT",
        Instant.parse("2015-01-16T08:00:00Z"));
    setReceivedAt(it.member(approved, it.submitter(), code, "0.9", at), "2015-01-15T11:00");
    setReceivedAt(it.member(approved, it.submitter(), code, "0.9", at), "2015-01-15T12:00");
    setReceivedAt(it.member(rejected, it.submitter(), code, "0.9", at), "2015-01-17T22:00");

    var result =
        it.body(
            it.send(
                "GET",
                ADMIN + "/stats?from=2015-01-15T12:00:00Z&to=2015-01-18T01:00:00Z",
                admin.token(),
                null),
            200);

    assertThat(result.get("submissionsByDay"))
        .extracting(day -> day.get("date").asString() + "=" + day.get("count").asInt())
        .containsExactly("2015-01-15=1", "2015-01-16=0", "2015-01-17=1", "2015-01-18=0");
    assertThat(result.at("/decisions/approved").asLong()).isEqualTo(2);
    assertThat(result.at("/decisions/rejected").asLong()).isEqualTo(1);
    assertThat(result.at("/processingTime/samples").asLong()).isEqualTo(2);
    assertThat(result.at("/processingTime/averageMs").asDouble()).isEqualTo(9_000_000);
    assertThat(result.get("signsByCategory"))
        .filteredOn(row -> category.equals(row.get("categoryCode").asString()))
        .singleElement()
        .satisfies(row -> {
          assertThat(row.get("name").asString()).isEqualTo("Nhóm thử");
          assertThat(row.get("count").asLong()).isEqualTo(3);
        });
    assertThat(result.get("densityCells"))
        .filteredOn(row ->
            Math.abs(row.at("/location/lat").asDouble() - at.lat()) < 1e-8
                && Math.abs(row.at("/location/lng").asDouble() - at.lng()) < 1e-8)
        .singleElement()
        .satisfies(row -> assertThat(row.get("count").asLong()).isEqualTo(3));
  }

  private void setReceivedAt(String id, String receivedAt) {
    jdbc.update(
        "UPDATE submissions SET received_at = ? WHERE id = ?", LocalDateTime.parse(receivedAt), id);
  }
}
