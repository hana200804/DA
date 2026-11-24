package vn.signa.sign.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;
import tools.jackson.databind.json.JsonMapper;
import vn.signa.support.IntegrationTest;

@DirtiesContext
@TestPropertySource(
    properties = {
      "signa.clustering.worker.enabled=true",
      "signa.clustering.crop-cleanup.enabled=false"
    })
abstract class AutoApprovalWorkerSupport extends IntegrationTest {

  @LocalServerPort int port;
  @Autowired JsonMapper json;
  @Autowired JdbcTemplate jdbc;
  @Autowired SubmissionStream stream;
  @Autowired ClusteringWorker worker;

  void verifyWorkerDecision(boolean enabled) {
    var it = new ReviewItSupport(port, json, jdbc);
    String category = it.category("AA");
    String code = it.type("A", category);
    var at = ReviewItSupport.somewhere();
    var members = new ArrayList<String>();

    assertThat(worker.isRunning()).isTrue();
    for (int i = 0; i < 3; i++) {
      String id = it.received(it.submitter(), code, ReviewItSupport.north(at, i * 3), "0.95");
      members.add(id);
      stream.add(id);
      await()
          .atMost(Duration.ofSeconds(15))
          .untilAsserted(() ->
              assertThat(it.submissionRow(id))
                  .containsEntry(
                      "status", enabled && members.size() == 3 ? "ACCEPTED" : "GROUPED"));
    }

    String signId = it.submissionRow(members.getFirst()).get("sign_id").toString();
    assertThat(members)
        .allSatisfy(id ->
            assertThat(it.submissionRow(id))
                .containsEntry("sign_id", signId)
                .containsEntry("status", enabled ? "ACCEPTED" : "GROUPED")
                .containsEntry("reject_reason", null));

    Map<String, Object> sign = it.signRow(signId);
    var decisions =
        it.history(signId).stream()
            .filter(event -> "AUTO_APPROVED".equals(event.get("action")))
            .toList();
    assertThat(sign).containsEntry("reviewed_by", null);
    if (enabled) {
      assertThat(sign).containsEntry("status", "APPROVED").doesNotContainEntry("reviewed_at", null);
      assertThat(decisions)
          .singleElement()
          .satisfies(event -> {
            assertThat(event).containsEntry("actor_type", "SYSTEM").containsEntry("actor_id", null);
            assertThat(it.jsonColumn(event.get("before_value")).get("status").asString())
                .isEqualTo("PENDING");
            assertThat(it.jsonColumn(event.get("after_value")).get("status").asString())
                .isEqualTo("APPROVED");
          });
    } else {
      assertThat(sign).containsEntry("status", "PENDING").containsEntry("reviewed_at", null);
      assertThat(decisions).isEmpty();
    }
  }
}
