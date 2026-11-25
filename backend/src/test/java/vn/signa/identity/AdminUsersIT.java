package vn.signa.identity;

import static org.assertj.core.api.Assertions.assertThat;

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

/** Quản lý người dùng qua HTTP thật với MySQL, Redis (FR-36, UT-B11). */
class AdminUsersIT extends IntegrationTest {

  @LocalServerPort int port;
  @Autowired JsonMapper json;
  @Autowired JdbcTemplate jdbc;

  private ApiClient api;

  @BeforeEach
  void client() {
    api = new ApiClient(port, json, jdbc);
  }

  private Reply patch(Account actor, String id, String body, String requestId) throws Exception {
    return api.call("PATCH", "/api/v1/admin/users/" + id, actor.token(), body, requestId);
  }

  @Test
  void lockingRevokesOldSessionsAndUnlockingAllowsLoginAgain() throws Exception {
    Account admin = api.newAccount("ADMIN");
    Account target = api.newAccount("USER");
    assertThat(api.call("GET", "/api/v1/me", target.token(), null).status()).isEqualTo(200);

    assertThat(patch(admin, target.id(), "{\"status\":\"LOCKED\"}", null).status()).isEqualTo(204);

    Reply old = api.call("GET", "/api/v1/me", target.token(), null);
    assertThat(old.status()).isEqualTo(401);
    assertThat(old.body().get("code").asString()).isEqualTo("UNAUTHENTICATED");
    assertThat(api.login(target.email()).status()).isEqualTo(403);
    assertThat(
            jdbc.queryForObject("SELECT status FROM users WHERE id = ?", String.class, target.id()))
        .isEqualTo("LOCKED");

    assertThat(patch(admin, target.id(), "{\"status\":\"ACTIVE\"}", null).status()).isEqualTo(204);
    assertThat(api.login(target.email()).status()).isEqualTo(200);
    assertThat(
            jdbc.queryForList(
                "SELECT action FROM audit_logs WHERE entity_type = 'USER' AND entity_id = ?"
                    + " ORDER BY id",
                String.class,
                target.id()))
        .containsExactly("LOCKED", "UNLOCKED");
  }

  @Test
  void roleChangeWritesHistoryWithBeforeAfterAndRevokesSessions() throws Exception {
    Account admin = api.newAccount("ADMIN");
    Account target = api.newAccount("USER");
    String requestId = "it-role-" + UUID.randomUUID();

    assertThat(patch(admin, target.id(), "{\"role\":\"MODERATOR\"}", requestId).status())
        .isEqualTo(204);

    assertThat(api.call("GET", "/api/v1/me", target.token(), null).status()).isEqualTo(401);
    Reply history =
        api.call(
            "GET",
            "/api/v1/admin/audit-logs?entityType=USER&entityId=" + target.id(),
            admin.token(),
            null);
    assertThat(history.status()).isEqualTo(200);
    assertThat(history.body().get("total").asLong()).isEqualTo(1);
    JsonNode row = history.body().get("items").get(0);
    assertThat(row.get("action").asString()).isEqualTo("ROLE_CHANGED");
    assertThat(row.get("actorType").asString()).isEqualTo("USER");
    assertThat(row.get("actorId").asString()).isEqualTo(admin.id());
    assertThat(row.get("actorName").asString()).isEqualTo(admin.displayName());
    assertThat(row.get("beforeValue").isObject()).isTrue();
    assertThat(row.get("beforeValue").get("role").asString()).isEqualTo("USER");
    assertThat(row.get("afterValue").get("role").asString()).isEqualTo("MODERATOR");
    assertThat(row.get("requestId").asString()).isEqualTo(requestId);

    Account promoted =
        new Account(
            target.id(),
            target.email(),
            target.displayName(),
            api.login(target.email()).body().get("token").asString());
    assertThat(api.call("GET", "/api/v1/admin/audit-logs", promoted.token(), null).status())
        .isEqualTo(200);
  }

  @Test
  void moderatorCannotManageUsers() throws Exception {
    Account moderator = api.newAccount("MODERATOR");
    Account target = api.newAccount("USER");

    Reply list = api.call("GET", "/api/v1/admin/users", moderator.token(), null);
    assertThat(list.status()).isEqualTo(403);
    assertThat(list.body().get("code").asString()).isEqualTo("FORBIDDEN");
    assertThat(patch(moderator, target.id(), "{\"status\":\"LOCKED\"}", null).status())
        .isEqualTo(403);
    assertThat(api.call("GET", "/api/v1/admin/audit-logs", moderator.token(), null).status())
        .isEqualTo(200);
  }

  @Test
  void adminCannotLockOrDemoteThemself() throws Exception {
    Account admin = api.newAccount("ADMIN");

    for (String body : new String[] {"{\"status\":\"LOCKED\"}", "{\"role\":\"USER\"}"}) {
      Reply reply = patch(admin, admin.id(), body, null);
      assertThat(reply.status()).isEqualTo(422);
      assertThat(reply.body().get("code").asString()).isEqualTo("BUSINESS_RULE");
    }
    assertThat(api.call("GET", "/api/v1/me", admin.token(), null).status()).isEqualTo(200);
  }

  @Test
  void searchEscapesWildcardsAndUnknownUserIs404() throws Exception {
    Account admin = api.newAccount("ADMIN");
    Account target = api.newAccount("USER");
    String part = target.email().substring(3, 20).toUpperCase();

    Reply found =
        api.call("GET", "/api/v1/admin/users?q=" + part + "&role=USER", admin.token(), null);
    assertThat(found.status()).isEqualTo(200);
    assertThat(found.body().get("total").asLong()).isEqualTo(1);
    JsonNode item = found.body().get("items").get(0);
    assertThat(item.get("id").asString()).isEqualTo(target.id());
    assertThat(item.get("email").asString()).isEqualTo(target.email());
    assertThat(item.get("status").asString()).isEqualTo("ACTIVE");
    assertThat(item.get("lastLoginAt").isNull()).isFalse();
    assertThat(item.has("passwordHash")).isFalse();

    // Không escape thì "_" và "%" khớp mọi email; có escape thì chỉ khớp email chứa đúng ký tự đó.
    for (String[] wildcard : new String[][] {{"_", "_"}, {"%25", "%"}}) {
      Reply matched = api.call("GET", "/api/v1/admin/users?q=" + wildcard[0], admin.token(), null);
      long containing =
          jdbc.queryForObject(
              "SELECT COUNT(*) FROM users WHERE INSTR(email, ?) > 0", Long.class, wildcard[1]);
      long all = jdbc.queryForObject("SELECT COUNT(*) FROM users", Long.class);
      assertThat(matched.status()).isEqualTo(200);
      assertThat(matched.body().get("total").asLong()).as(wildcard[1]).isEqualTo(containing);
      assertThat(containing).isLessThan(all);
    }

    String unknown = UUID.randomUUID().toString();
    assertThat(patch(admin, unknown, "{\"status\":\"LOCKED\"}", null).status()).isEqualTo(404);
  }
}
