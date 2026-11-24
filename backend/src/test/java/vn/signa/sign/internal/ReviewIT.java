package vn.signa.sign.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doAnswer;
import static vn.signa.sign.internal.ReviewItSupport.ADMIN;
import static vn.signa.sign.internal.ReviewItSupport.assertStatus;
import static vn.signa.sign.internal.ReviewItSupport.north;
import static vn.signa.sign.internal.ReviewItSupport.somewhere;

import java.math.BigDecimal;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import vn.signa.catalog.api.SignCatalog;
import vn.signa.sign.clustering.GeoPoint;
import vn.signa.sign.internal.ReviewItSupport.Session;
import vn.signa.support.IntegrationTest;

/** Phê duyệt và sửa biển qua HTTP với MySQL, Redis, MinIO thật (docs/08). Chạy trên CI. */
class ReviewIT extends IntegrationTest {

  @LocalServerPort int port;
  @Autowired JsonMapper json;
  @Autowired JdbcTemplate jdbc;
  @Autowired ClusteringProcessor processor;
  @MockitoSpyBean SignCatalog catalog;

  private ReviewItSupport it;
  private String category;
  private String codeA;
  private String codeB;

  @BeforeEach
  void fixtures() {
    it = new ReviewItSupport(port, json, jdbc);
    category = it.category("RV");
    codeA = it.type("A", category);
    codeB = it.type("B", category);
  }

  @Test
  void itB04TwoModeratorsApprovingSameVersionOneWins() throws Exception {
    Session first = it.moderator();
    Session second = it.moderator();
    GeoPoint at = somewhere();
    String sign = it.sign(codeA, category, at, "PENDING", "0.7368", "WAIT", Instant.now());
    it.member(sign, it.submitter(), codeA, "0.9000", at);
    int version = it.version(sign);

    CyclicBarrier start = new CyclicBarrier(2);
    List<CompletableFuture<HttpResponse<String>>> calls = new ArrayList<>();
    for (Session moderator : List.of(first, second)) {
      calls.add(
          CompletableFuture.supplyAsync(() -> {
            try {
              start.await(10, TimeUnit.SECONDS);
              return it.approve(moderator, sign, Map.of("version", version));
            } catch (Exception e) {
              throw new IllegalStateException(e);
            }
          }));
    }
    List<HttpResponse<String>> responses = new ArrayList<>();
    for (var call : calls) responses.add(call.get(30, TimeUnit.SECONDS));

    assertThat(responses).extracting(HttpResponse::statusCode).containsExactlyInAnyOrder(204, 409);
    HttpResponse<String> lost =
        responses.stream().filter(r -> r.statusCode() == 409).findFirst().orElseThrow();
    assertThat(json.readTree(lost.body()).get("code").asString())
        .isEqualTo("SIGN_ALREADY_PROCESSED");
    assertThat(it.signRow(sign))
        .containsEntry("status", "APPROVED")
        .containsEntry("version", version + 1);
    assertThat(it.history(sign)).extracting(row -> row.get("action")).containsExactly("APPROVED");
  }

  @Test
  void itB05ApproveEditRemoveWritesThreeHistoryRows() throws Exception {
    Session moderator = it.moderator();
    GeoPoint at = somewhere();
    GeoPoint moved = north(at, 3);
    String sign = it.sign(codeA, category, at, "PENDING", "0.7368", "WAIT", Instant.now());
    it.member(sign, it.submitter(), codeA, "0.9000", at);

    assertStatus(
        it.approve(
            moderator,
            sign,
            Map.of("version", 0, "location", Map.of("lat", moved.lat(), "lng", moved.lng()))),
        204);
    assertStatus(
        it.send(
            "PATCH",
            ADMIN + "/signs/" + sign,
            moderator.token(),
            Map.of("version", 1, "headingDeg", 180)),
        204);
    assertStatus(
        it.send(
            "POST",
            ADMIN + "/signs/" + sign + "/remove",
            moderator.token(),
            Map.of("version", 2, "reason", "Biển đã bị tháo")),
        204);

    List<Map<String, Object>> history = it.history(sign);
    assertThat(history)
        .extracting(row -> row.get("action"))
        .containsExactly("APPROVED", "EDITED", "REMOVED");
    assertThat(history)
        .allSatisfy(row -> {
          assertThat(row).containsEntry("actor_type", "USER");
          assertThat(row).containsEntry("actor_id", moderator.id());
          assertThat(row.get("request_id")).isNotNull();
        });

    JsonNode approvedBefore = it.jsonColumn(history.get(0).get("before_value"));
    JsonNode approvedAfter = it.jsonColumn(history.get(0).get("after_value"));
    assertThat(approvedBefore.get("status").asString()).isEqualTo("PENDING");
    assertThat(approvedBefore.get("location").get("lat").asDouble()).isEqualTo(at.lat());
    assertThat(approvedBefore.get("locationLocked").asBoolean()).isFalse();
    assertThat(approvedAfter.get("status").asString()).isEqualTo("APPROVED");
    assertThat(approvedAfter.get("location").get("lat").asDouble()).isEqualTo(moved.lat());
    assertThat(approvedAfter.get("locationLocked").asBoolean()).isTrue();
    // α = 0,5 + 0,9 + 2 = 3,4; β = 0,5.
    assertThat(approvedAfter.get("confidence").decimalValue()).isEqualByComparingTo("0.8718");

    JsonNode editedBefore = it.jsonColumn(history.get(1).get("before_value"));
    JsonNode editedAfter = it.jsonColumn(history.get(1).get("after_value"));
    assertThat(editedBefore.propertyNames()).containsExactly("headingDeg");
    assertThat(editedBefore.get("headingDeg").asInt()).isEqualTo(90);
    assertThat(editedAfter.get("headingDeg").asInt()).isEqualTo(180);

    JsonNode removedBefore = it.jsonColumn(history.get(2).get("before_value"));
    JsonNode removedAfter = it.jsonColumn(history.get(2).get("after_value"));
    assertThat(removedBefore.propertyNames()).containsExactly("status");
    assertThat(removedBefore.get("status").asString()).isEqualTo("APPROVED");
    assertThat(removedAfter.get("status").asString()).isEqualTo("REMOVED");
    assertThat(history.get(2)).containsEntry("reason", "Biển đã bị tháo");

    assertThat(it.signRow(sign))
        .containsEntry("status", "REMOVED")
        .containsEntry("version", 3)
        .containsEntry("heading_deg", 180);
    // Tài khoản backend chỉ có SELECT, INSERT trên audit_logs (FR-23).
    assertThatThrownBy(
            () -> jdbc.update("UPDATE audit_logs SET reason = 'x' WHERE entity_id = ?", sign))
        .isInstanceOf(DataAccessException.class);
    assertThatThrownBy(() -> jdbc.update("DELETE FROM audit_logs WHERE entity_id = ?", sign))
        .isInstanceOf(DataAccessException.class);
  }

  @Test
  void approvingNonLeadingCodeKeepsConfidenceAtLeastHalf() throws Exception {
    Session moderator = it.moderator();
    GeoPoint at = somewhere();
    List<String> leading = new ArrayList<>();
    for (int i = 0; i < 3; i++) {
      String id = it.received(it.submitter(), codeA, north(at, i), "0.9");
      processor.process(id);
      leading.add(id);
    }
    String other = it.received(it.submitter(), codeB, north(at, 4), "0.9");
    processor.process(other);
    String sign = it.submissionRow(other).get("sign_id").toString();
    assertThat(leading)
        .allSatisfy(id -> assertThat(it.submissionRow(id)).containsEntry("sign_id", sign));
    assertThat(it.signRow(sign))
        .containsEntry("sign_type_code", codeA)
        .containsEntry("cover_crop_key", leading.getFirst() + ".jpg");

    assertStatus(
        it.approve(moderator, sign, Map.of("version", it.version(sign), "signTypeCode", codeB)),
        204);

    Map<String, Object> row = it.signRow(sign);
    assertThat(row)
        .containsEntry("status", "APPROVED")
        .containsEntry("sign_type_code", codeB)
        .containsEntry("reviewed_by", moderator.id())
        .containsEntry("suggestion", null);
    assertThat((BigDecimal) row.get("confidence")).isGreaterThanOrEqualTo(new BigDecimal("0.5"));
    assertThat((BigDecimal) row.get("moderator_boost")).isEqualByComparingTo("2");
    // sign_code_weights giữ nguyên; W_A có trước lần duyệt nên không vào β.
    assertThat(it.weight(sign, codeA)).isEqualByComparingTo("2.7");
    assertThat(it.weight(sign, codeB)).isEqualByComparingTo("0.9");
    assertThat(leading)
        .allSatisfy(id ->
            assertThat(it.submissionRow(id))
                .containsEntry("status", "REJECTED")
                .containsEntry("reject_reason", "Biển được duyệt với mã khác"));
    assertThat(it.submissionRow(other)).containsEntry("status", "ACCEPTED");
    assertThat(row).containsEntry("cover_crop_key", other + ".jpg");
    JsonNode detail = it.body(it.send("GET", "/api/v1/signs/" + sign, null, null), 200);
    assertThat(detail.get("confidence").asDouble()).isGreaterThanOrEqualTo(0.5);
    assertThat(detail.get("coverCropUrl").asString()).contains(other + ".jpg");
  }

  @Test
  void queueSortsApproveFirstThenConfidenceThenAge() throws Exception {
    Session moderator = it.moderator();
    GeoPoint at = somewhere();
    Instant t0 = Instant.parse("2026-11-01T00:00:00Z");
    String waitHigh = it.sign(codeA, category, at, "PENDING", "0.9500", "WAIT", t0.plusSeconds(10));
    String approveNew =
        it.sign(codeA, category, north(at, 30), "PENDING", "0.8200", "APPROVE", t0.plusSeconds(20));
    String review =
        it.sign(codeA, category, north(at, 60), "UNDER_REVIEW", "0.4000", "INSPECT", t0);
    String approveTop =
        it.sign(codeA, category, north(at, 90), "PENDING", "0.9000", "APPROVE", t0.plusSeconds(30));
    String approveOld =
        it.sign(codeA, category, north(at, 120), "PENDING", "0.8200", "APPROVE", t0);
    it.sign(codeA, category, north(at, 150), "APPROVED", "0.9900", null, t0);
    it.sign(codeA, category, north(at, 180), "REJECTED", "0.9900", null, t0);
    String queue = ADMIN + "/review-queue?categories=" + category;

    JsonNode all = it.body(it.send("GET", queue + "&size=100", moderator.token(), null), 200);
    assertThat(ids(all)).containsExactly(approveTop, approveOld, approveNew, waitHigh, review);
    assertThat(all.get("total").asLong()).isEqualTo(5);
    assertThat(all.get("items").get(0).get("signName").asString()).isEqualTo("Loại A");
    assertThat(all.get("items").get(0).get("distinctSubmitters").asInt()).isEqualTo(1);

    JsonNode second =
        it.body(it.send("GET", queue + "&page=1&size=2", moderator.token(), null), 200);
    assertThat(ids(second)).containsExactly(approveNew, waitHigh);
    assertThat(second.get("page").asInt()).isEqualTo(1);

    JsonNode underReview =
        it.body(it.send("GET", queue + "&status=UNDER_REVIEW", moderator.token(), null), 200);
    assertThat(ids(underReview)).containsExactly(review);
    JsonNode approve =
        it.body(it.send("GET", queue + "&suggestion=APPROVE", moderator.token(), null), 200);
    assertThat(ids(approve)).containsExactly(approveTop, approveOld, approveNew);
    String bbox =
        (at.lng() - 0.001)
            + ","
            + (at.lat() - 0.0001)
            + ","
            + (at.lng() + 0.001)
            + ","
            + (at.lat() + 0.0001);
    JsonNode boxed = it.body(it.send("GET", queue + "&bbox=" + bbox, moderator.token(), null), 200);
    assertThat(ids(boxed)).containsExactly(waitHigh);
  }

  @Test
  void queueCountUsesTheSnapshotFromBeforeAConcurrentApproval() throws Exception {
    Session moderator = it.moderator();
    String sign = it.sign(codeA, category, somewhere(), "PENDING", "0.7", "WAIT", Instant.now());
    doAnswer(
            invocation -> {
              CompletableFuture.runAsync(
                      () -> jdbc.update("UPDATE signs SET status = 'APPROVED' WHERE id = ?", sign))
                  .get(10, TimeUnit.SECONDS);
              return invocation.callRealMethod();
            })
        .when(catalog)
        .findType(codeA);

    String path =
        ADMIN + "/review-queue?status=PENDING&bbox=-180,-90,180,90&categories=" + category;
    JsonNode before = it.body(it.send("GET", path, moderator.token(), null), 200);
    assertThat(ids(before)).containsExactly(sign);
    assertThat(before.get("total").asLong()).isEqualTo(1);
    JsonNode after = it.body(it.send("GET", path, moderator.token(), null), 200);
    assertThat(ids(after)).isEmpty();
    assertThat(after.get("total").asLong()).isZero();
  }

  @Test
  void detailListsSubmissionsAndNearbySignsWithoutItself() throws Exception {
    Session moderator = it.moderator();
    GeoPoint at = somewhere();
    String self = it.sign(codeA, category, at, "APPROVED", "0.9000", null, Instant.now());
    String member = it.member(self, it.submitter(), codeA, "0.9000", at);
    String close =
        it.sign(codeB, category, north(at, 10), "UNDER_REVIEW", "0.4", "INSPECT", Instant.now());
    String near = it.sign(codeA, category, north(at, 40), "APPROVED", "0.9", null, Instant.now());
    it.sign(codeA, category, north(at, 5), "PENDING", "0.7", "WAIT", Instant.now());
    it.sign(codeA, category, north(at, 80), "APPROVED", "0.9", null, Instant.now());

    JsonNode detail = it.detail(moderator, self);

    List<String> nearby = new ArrayList<>();
    detail.get("nearbySigns").forEach(n -> nearby.add(n.get("id").asString()));
    assertThat(nearby).containsExactly(close, near).doesNotContain(self);
    assertThat(detail.get("nearbySigns").get(0).get("status").asString()).isEqualTo("UNDER_REVIEW");
    assertThat(detail.get("version").asInt()).isZero();
    assertThat(detail.get("codeWeights").get(codeA).asDouble()).isEqualTo(0.9);
    JsonNode submission = detail.get("submissions").get(0);
    assertThat(submission.get("id").asString()).isEqualTo(member);
    assertThat(submission.get("cropUrl").asString()).contains(member + ".jpg").contains("X-Amz-");
    assertThat(
            it.send("GET", ADMIN + "/signs/" + ReviewItSupport.uuid(), moderator.token(), null)
                .statusCode())
        .isEqualTo(404);
  }

  @Test
  void rejectRejectsMembersAndKeepReapprovesUnderReview() throws Exception {
    Session moderator = it.moderator();
    GeoPoint at = somewhere();
    String pending = it.sign(codeA, category, at, "PENDING", "0.7", "WAIT", Instant.now());
    String member = it.member(pending, it.submitter(), codeA, "0.9000", at);

    assertStatus(
        it.send(
            "POST",
            ADMIN + "/signs/" + pending + "/reject",
            moderator.token(),
            Map.of("version", 0, "reason", "Không có biển")),
        204);
    assertThat(it.signRow(pending)).containsEntry("status", "REJECTED");
    assertThat(it.submissionRow(member))
        .containsEntry("status", "REJECTED")
        .containsEntry("reject_reason", "Không có biển");
    // Biển đã từ chối không duyệt được nữa.
    assertStatus(it.approve(moderator, pending, Map.of("version", 1)), 409);

    GeoPoint other = north(at, 200);
    String review =
        it.sign(codeA, category, other, "UNDER_REVIEW", "0.4", "INSPECT", Instant.now());
    String grouped = it.member(review, it.submitter(), codeA, "0.9000", other);
    assertStatus(
        it.send(
            "POST",
            ADMIN + "/signs/" + review + "/keep",
            moderator.token(),
            Map.of("version", 0, "headingDeg", 45)),
        204);
    assertThat(it.signRow(review))
        .containsEntry("status", "APPROVED")
        .containsEntry("heading_deg", 45)
        .containsEntry("version", 1);
    assertThat(it.submissionRow(grouped)).containsEntry("status", "ACCEPTED");
    assertThat(it.history(review))
        .extracting(row -> row.get("action"))
        .containsExactly("EDITED", "KEPT");
  }

  @Test
  void keepSelectsTheNewCodesCropAndPatchClearsItWhenNoCropMatches() throws Exception {
    Session moderator = it.moderator();
    GeoPoint at = somewhere();
    String sign = it.sign(codeA, category, at, "UNDER_REVIEW", "0.4", "INSPECT", Instant.now());
    String old = it.member(sign, it.submitter(), codeA, "0.9000", at);
    String replacement = it.member(sign, it.submitter(), codeB, "0.8000", at);
    jdbc.update("UPDATE signs SET cover_crop_key = ? WHERE id = ?", old + ".jpg", sign);
    String path = ADMIN + "/signs/" + sign;

    assertStatus(
        it.send(
            "POST", path + "/keep", moderator.token(), Map.of("version", 0, "signTypeCode", codeB)),
        204);
    assertThat(it.signRow(sign)).containsEntry("cover_crop_key", replacement + ".jpg");

    String codeWithoutCrop = it.type("C", category);
    assertStatus(
        it.send(
            "PATCH",
            path,
            moderator.token(),
            Map.of("version", 1, "signTypeCode", codeWithoutCrop)),
        204);
    assertThat(it.signRow(sign)).containsEntry("cover_crop_key", null);
  }

  @Test
  void createdSignIsApprovedLockedWithHistory() throws Exception {
    Session moderator = it.moderator();
    GeoPoint at = somewhere();

    JsonNode created =
        it.body(
            it.send(
                "POST",
                ADMIN + "/signs",
                moderator.token(),
                Map.of(
                    "signTypeCode", codeB, "location", Map.of("lat", at.lat(), "lng", at.lng()))),
            201);

    String id = created.get("id").asString();
    assertThat(created.get("version").asInt()).isZero();
    assertThat(it.signRow(id))
        .containsEntry("status", "APPROVED")
        .containsEntry("location_locked", true)
        .containsEntry("reviewed_by", moderator.id());
    assertThat((BigDecimal) it.signRow(id).get("confidence")).isEqualByComparingTo("0.8333");
    assertThat(it.history(id)).extracting(row -> row.get("action")).containsExactly("CREATED");
    JsonNode inactive =
        it.body(
            it.send(
                "POST",
                ADMIN + "/signs",
                moderator.token(),
                Map.of("signTypeCode", "KHONG.CO", "location", Map.of("lat", 0, "lng", 0))),
            422);
    assertThat(inactive.get("code").asString()).isEqualTo("BUSINESS_RULE");
  }

  private static List<String> ids(JsonNode page) {
    List<String> ids = new ArrayList<>();
    page.get("items").forEach(item -> ids.add(item.get("id").asString()));
    return ids;
  }
}
