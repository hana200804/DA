package vn.signa.audit;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import vn.signa.support.ApiClient;
import vn.signa.support.ApiClient.Account;
import vn.signa.support.ApiClient.Reply;
import vn.signa.support.IntegrationTest;

/** Xem lịch sử qua HTTP thật (FR-34): lọc theo khoảng thời gian, thứ tự, tên người thực hiện. */
class AuditLogsIT extends IntegrationTest {

  @LocalServerPort int port;
  @Autowired JsonMapper json;
  @Autowired JdbcTemplate jdbc;

  private ApiClient api;

  @BeforeEach
  void client() {
    api = new ApiClient(port, json, jdbc);
  }

  private void insert(String signId, String action, String actorId, Instant at) {
    jdbc.update(
        """
        INSERT INTO audit_logs (entity_type, entity_id, action, actor_type, actor_id,
                                before_value, after_value, created_at)
        VALUES ('SIGN', ?, ?, ?, ?, '{"status":"PENDING"}', '{"status":"APPROVED"}', ?)
        """,
        signId,
        action,
        actorId == null ? "SYSTEM" : "USER",
        actorId,
        Timestamp.from(at));
  }

  private Reply logs(Account moderator, String query) throws Exception {
    return api.call("GET", "/api/v1/admin/audit-logs?" + query, moderator.token(), null);
  }

  private static List<String> actions(Reply reply) {
    List<String> actions = new ArrayList<>();
    reply.body().get("items").forEach(row -> actions.add(row.get("action").asString()));
    return actions;
  }

  @Test
  void filtersByTimeRangeWithInclusiveBoundsNewestFirst() throws Exception {
    Account moderator = api.newAccount("MODERATOR");
    String sign = UUID.randomUUID().toString();
    Instant from = Instant.parse("2026-11-10T00:00:00Z");
    Instant to = Instant.parse("2026-11-10T23:59:59.999Z");
    insert(sign, "CREATED", null, from.minusMillis(1));
    insert(sign, "LEADING_CODE_CHANGED", null, from);
    insert(sign, "APPROVED", moderator.id(), Instant.parse("2026-11-10T12:00:00Z"));
    insert(sign, "EDITED", moderator.id(), to);
    insert(sign, "REMOVED", moderator.id(), to.plusMillis(1));

    Reply ranged =
        logs(moderator, "entityType=SIGN&entityId=" + sign + "&from=" + from + "&to=" + to);

    assertThat(ranged.status()).isEqualTo(200);
    assertThat(ranged.body().get("total").asLong()).isEqualTo(3);
    assertThat(actions(ranged)).containsExactly("EDITED", "APPROVED", "LEADING_CODE_CHANGED");
    JsonNode edited = ranged.body().get("items").get(0);
    assertThat(edited.get("createdAt").asString()).isEqualTo("2026-11-10T23:59:59.999Z");
    assertThat(edited.get("actorName").asString()).isEqualTo(moderator.displayName());
    assertThat(edited.get("beforeValue").get("status").asString()).isEqualTo("PENDING");
    JsonNode system = ranged.body().get("items").get(2);
    assertThat(system.get("actorType").asString()).isEqualTo("SYSTEM");
    assertThat(system.get("actorId").isNull()).isTrue();
    assertThat(system.get("actorName").isNull()).isTrue();

    assertThat(actions(logs(moderator, "entityId=" + sign + "&from=" + to.plusMillis(1))))
        .containsExactly("REMOVED");
    assertThat(actions(logs(moderator, "entityId=" + sign + "&to=" + from.minusMillis(1))))
        .containsExactly("CREATED");
  }

  @Test
  void filtersByActorAndActionAndPages() throws Exception {
    Account moderator = api.newAccount("MODERATOR");
    String sign = UUID.randomUUID().toString();
    Instant at = Instant.parse("2026-11-11T08:00:00Z");
    for (int i = 0; i < 3; i++) {
      insert(sign, "APPROVED", moderator.id(), at.plusSeconds(i));
    }
    insert(sign, "REJECTED", moderator.id(), at.plusSeconds(10));

    Reply byActor = logs(moderator, "actorId=" + moderator.id() + "&action=APPROVED&page=1&size=2");
    assertThat(byActor.body().get("total").asLong()).isEqualTo(3);
    assertThat(byActor.body().get("page").asInt()).isEqualTo(1);
    assertThat(byActor.body().get("size").asInt()).isEqualTo(2);
    assertThat(byActor.body().get("items")).hasSize(1);
    assertThat(byActor.body().get("items").get(0).get("createdAt").asString())
        .isEqualTo("2026-11-11T08:00:00Z");
  }

  @Test
  void fromAfterToIs400AndUserWithoutAuditReadIs403() throws Exception {
    Account moderator = api.newAccount("MODERATOR");
    Account user = api.newAccount("USER");

    Reply bad = logs(moderator, "from=2026-11-02T00:00:00Z&to=2026-11-01T00:00:00Z");
    assertThat(bad.status()).isEqualTo(400);
    assertThat(bad.body().get("code").asString()).isEqualTo("VALIDATION_ERROR");
    assertThat(logs(user, "").status()).isEqualTo(403);
    assertThat(api.call("DELETE", "/api/v1/admin/audit-logs", moderator.token(), null).status())
        .isEqualTo(404);
  }
}
