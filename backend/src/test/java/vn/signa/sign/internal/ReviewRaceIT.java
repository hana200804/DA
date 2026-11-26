package vn.signa.sign.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static vn.signa.sign.internal.ReviewItSupport.assertStatus;
import static vn.signa.sign.internal.ReviewItSupport.north;
import static vn.signa.sign.internal.ReviewItSupport.somewhere;

import java.math.BigDecimal;
import java.net.http.HttpResponse;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.json.JsonMapper;
import vn.signa.sign.clustering.GeoPoint;
import vn.signa.sign.internal.ReviewItSupport.Session;
import vn.signa.support.IntegrationTest;

/**
 * Kiểm duyệt viên duyệt trong lúc worker gom cụm gán đề xuất mới vào cùng biển. Cả hai thay đổi
 * phải còn: worker khoá dòng biển ({@code SELECT … FOR UPDATE}) nên đọc được quyết định đã commit;
 * kiểm duyệt viên thua thì nhận {@code 409} và thử lại với {@code version} mới. Chạy trên CI.
 */
class ReviewRaceIT extends IntegrationTest {

  private static final int ROUNDS = 8;

  @LocalServerPort int port;
  @Autowired JsonMapper json;
  @Autowired JdbcTemplate jdbc;
  @Autowired ClusteringProcessor processor;

  private ReviewItSupport it;
  private String code;
  private Session moderator;

  @BeforeEach
  void fixtures() throws Exception {
    it = new ReviewItSupport(port, json, jdbc);
    code = it.type("R", it.category("RACE"));
    moderator = it.moderator();
  }

  @Test
  void workerAssigningAfterApprovalConfirmsApprovedSign() throws Exception {
    Pair pair = pendingSignAndNewSubmission();

    assertStatus(it.approve(moderator, pair.sign(), Map.of("version", 0)), 204);
    processor.process(pair.incoming());

    assertBothChangesKept(pair);
    assertThat(it.version(pair.sign())).isEqualTo(2);
  }

  @Test
  void moderatorWithStaleVersionGetsConflictThenRetries() throws Exception {
    Pair pair = pendingSignAndNewSubmission();
    int seen = it.detail(moderator, pair.sign()).get("version").asInt();

    processor.process(pair.incoming());
    HttpResponse<String> stale = it.approve(moderator, pair.sign(), Map.of("version", seen));
    assertStatus(stale, 409);
    assertThat(json.readTree(stale.body()).get("code").asString())
        .isEqualTo("SIGN_ALREADY_PROCESSED");

    int fresh = it.detail(moderator, pair.sign()).get("version").asInt();
    assertThat(fresh).isEqualTo(seen + 1);
    assertStatus(it.approve(moderator, pair.sign(), Map.of("version", fresh)), 204);

    assertBothChangesKept(pair);
  }

  @Test
  void concurrentApprovalAndAssignmentKeepBothChanges() throws Exception {
    for (int round = 0; round < ROUNDS; round++) {
      Pair pair = pendingSignAndNewSubmission();
      CyclicBarrier start = new CyclicBarrier(2);
      CompletableFuture<Void> worker =
          CompletableFuture.runAsync(() -> {
            await(start);
            processor.process(pair.incoming());
          });
      CompletableFuture<Integer> review =
          CompletableFuture.supplyAsync(() -> {
            await(start);
            return approveWithRetry(pair.sign());
          });

      worker.get(30, TimeUnit.SECONDS);
      assertThat(review.get(30, TimeUnit.SECONDS)).as("lượt %d", round).isEqualTo(204);
      assertBothChangesKept(pair);
    }
  }

  /** Duyệt; nhận {@code 409} thì đọc lại {@code version} và thử lại như web quản trị. */
  private int approveWithRetry(String sign) {
    try {
      int status = 0;
      for (int attempt = 0; attempt < 3; attempt++) {
        int version = it.detail(moderator, sign).get("version").asInt();
        status = it.approve(moderator, sign, Map.of("version", version)).statusCode();
        if (status != 409) return status;
      }
      return status;
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  private void assertBothChangesKept(Pair pair) {
    Map<String, Object> sign = it.signRow(pair.sign());
    assertThat(sign)
        .containsEntry("status", "APPROVED")
        .containsEntry("reviewed_by", moderator.id())
        .containsEntry("suggestion", null);
    assertThat((BigDecimal) sign.get("moderator_boost")).isEqualByComparingTo("2");
    assertThat(it.submissionRow(pair.existing())).containsEntry("status", "ACCEPTED");
    assertThat(it.submissionRow(pair.incoming()))
        .containsEntry("sign_id", pair.sign())
        .containsEntry("status", "ACCEPTED");
    // Hai người khác nhau, p = 0,9 và 0,8.
    assertThat(it.weight(pair.sign(), code)).isEqualByComparingTo("1.7");
    // α = 0,5 + 1,7 + 2 = 4,2; β = 0,5.
    assertThat((BigDecimal) sign.get("confidence")).isEqualByComparingTo("0.8936");
    assertThat(it.history(pair.sign()))
        .extracting(row -> row.get("action"))
        .containsOnlyOnce("APPROVED")
        .contains("CREATED");
  }

  private record Pair(String sign, String existing, String incoming) {}

  /** Biển {@code PENDING} tạo bởi worker từ một đề xuất, và một đề xuất mới chờ gom cùng chỗ. */
  private Pair pendingSignAndNewSubmission() {
    GeoPoint at = somewhere();
    String existing = it.received(it.submitter(), code, at, "0.9");
    processor.process(existing);
    String sign = it.submissionRow(existing).get("sign_id").toString();
    String incoming = it.received(it.submitter(), code, north(at, 2), "0.8");
    return new Pair(sign, existing, incoming);
  }

  private static void await(CyclicBarrier barrier) {
    try {
      barrier.await(10, TimeUnit.SECONDS);
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }
}
