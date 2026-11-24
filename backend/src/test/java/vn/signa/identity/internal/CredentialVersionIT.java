package vn.signa.identity.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;
import vn.signa.support.ApiClient;
import vn.signa.support.ApiClient.Account;
import vn.signa.support.ApiClient.Reply;
import vn.signa.support.IntegrationTest;

/** A1, B1, B10 với request thật và giao dịch MySQL, phiên Redis thật. */
class CredentialVersionIT extends IntegrationTest {

  private static final String ME = "/api/v1/me";
  private static final String NEW_PASSWORD = "mat-khau-moi-it-12345";

  @LocalServerPort int port;
  @Autowired JsonMapper json;
  @Autowired JdbcTemplate jdbc;
  @Autowired StringRedisTemplate redis;
  @Autowired UserAdminService adminUsers;
  @Autowired TransactionTemplate transactions;
  @MockitoSpyBean PasswordEncoder encoder;
  @MockitoSpyBean RedisSessionStore sessions;

  private ApiClient api;

  @BeforeEach
  void client() {
    api = new ApiClient(port, json, jdbc);
  }

  @Test
  void loginThatReadOldPasswordIsRejectedAfterPasswordChange() throws Exception {
    Account target = api.newAccount("USER");
    Reply stale =
        loginOverlapping(
            target,
            () -> {
              String body =
                  json.writeValueAsString(
                      Map.of("currentPassword", ApiClient.PASSWORD, "newPassword", NEW_PASSWORD));
              assertThat(api.call("PUT", ME + "/password", target.token(), body).status())
                  .isEqualTo(204);
            });

    assertRejected(stale);
    assertThat(me(target.token()).status()).isEqualTo(200);
    assertThat(version(target)).isEqualTo(1);
  }

  @Test
  void loginThatReadAdminRoleIsRejectedAfterDemotion() throws Exception {
    Account actor = api.newAccount("ADMIN");
    Account target = api.newAccount("ADMIN");
    Reply stale =
        loginOverlapping(
            target,
            () ->
                assertThat(
                        api.call(
                                "PATCH",
                                "/api/v1/admin/users/" + target.id(),
                                actor.token(),
                                "{\"role\":\"USER\"}")
                            .status())
                    .isEqualTo(204));

    assertRejected(stale);
    assertThat(me(target.token()).status()).isEqualTo(401);
    Reply next = api.login(target.email());
    assertThat(next.status()).isEqualTo(200);
    assertThat(next.body().get("user").get("role").asString()).isEqualTo("USER");
  }

  @Test
  void redisErrorInRolledBackTransactionLeavesBothSessionsValid() throws Exception {
    Account actor = api.newAccount("ADMIN");
    Account target = api.newAccount("USER");
    String second = api.login(target.email()).body().get("token").asString();

    // Lỗi Redis thật giữa các bước của một giao dịch MySQL. Cleanup đã được đăng ký.
    // RPUSH vào hash phiên làm Redis trả WRONGTYPE mà giữ nguyên hai phiên.
    assertThatThrownBy(() ->
            transactions.executeWithoutResult(status -> {
              adminUsers.update(actor.id(), target.id(), "MODERATOR", null);
              assertThat(version(target)).isEqualTo(1);
              redis
                  .opsForList()
                  .rightPush(RedisSessionStore.SESSION_PREFIX + SessionTokens.hash(second), "test");
            }))
        .isInstanceOf(org.springframework.dao.DataAccessException.class);

    verify(sessions, never()).deleteOlder(eq(target.id()), org.mockito.ArgumentMatchers.anyInt());
    assertThat(version(target)).isZero();
    assertThat(
            jdbc.queryForObject(
                "SELECT role_code FROM users WHERE id = ?", String.class, target.id()))
        .isEqualTo("USER");
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM audit_logs WHERE entity_id = ?", Long.class, target.id()))
        .isZero();
    assertThat(me(target.token()).status()).isEqualTo(200);
    assertThat(me(second).status()).isEqualTo(200);
  }

  @Test
  void committedRoleChangeRejectsSessionsEvenIfRedisCleanupFails() throws Exception {
    Account actor = api.newAccount("ADMIN");
    Account target = api.newAccount("USER");
    String second = api.login(target.email()).body().get("token").asString();
    doAnswer(
            invocation -> {
              redis.delete(RedisSessionStore.SESSION_PREFIX + SessionTokens.hash(target.token()));
              throw new QueryTimeoutException("Lỗi giữa hai lần dọn phiên");
            })
        .when(sessions)
        .deleteOlder(target.id(), 1);

    assertThat(
            api.call(
                    "PATCH",
                    "/api/v1/admin/users/" + target.id(),
                    actor.token(),
                    "{\"role\":\"MODERATOR\"}")
                .status())
        .isEqualTo(204);

    assertThat(version(target)).isEqualTo(1);
    assertThat(redis.hasKey(RedisSessionStore.SESSION_PREFIX + SessionTokens.hash(second)))
        .isTrue();
    assertThat(me(target.token()).status()).isEqualTo(401);
    assertThat(me(second).status()).isEqualTo(401);
  }

  @Test
  void currentSessionRecoversAfterRedisPromotionFailsAtCommit() throws Exception {
    Account target = api.newAccount("USER");
    String second = api.login(target.email()).body().get("token").asString();
    String currentId = SessionTokens.hash(target.token());
    QueryTimeoutException unavailable = new QueryTimeoutException("Redis tạm thời không sẵn sàng");
    doThrow(unavailable)
        .doThrow(unavailable)
        .doThrow(unavailable)
        .doCallRealMethod()
        .when(sessions)
        .promote(target.id(), currentId, 1);
    String body =
        json.writeValueAsString(
            Map.of("currentPassword", ApiClient.PASSWORD, "newPassword", NEW_PASSWORD));

    assertThat(api.call("PUT", ME + "/password", target.token(), body).status()).isEqualTo(204);
    assertThat(sessions.find(currentId)).get().extracting(Session::credentialVersion).isEqualTo(0);
    assertThat(me(second).status()).isEqualTo(401);
    assertThat(me(target.token()).status()).isEqualTo(200);
    assertThat(sessions.find(currentId)).get().extracting(Session::credentialVersion).isEqualTo(1);
  }

  private Reply loginOverlapping(Account target, Change change) throws Exception {
    String hash =
        jdbc.queryForObject(
            "SELECT password_hash FROM users WHERE id = ?", String.class, target.id());
    var afterBcrypt = new CountDownLatch(1);
    var resume = new CountDownLatch(1);
    var first = new AtomicBoolean(true);
    doAnswer(
            invocation -> {
              Object matches = invocation.callRealMethod();
              if (first.getAndSet(false)) {
                afterBcrypt.countDown();
                if (!resume.await(15, TimeUnit.SECONDS)) {
                  throw new IllegalStateException("Hết giờ chờ thay đổi xác thực");
                }
              }
              return matches;
            })
        .when(encoder)
        .matches(anyString(), eq(hash));

    try (var worker = Executors.newSingleThreadExecutor()) {
      var staleLogin = worker.submit(() -> api.login(target.email()));
      try {
        assertThat(afterBcrypt.await(15, TimeUnit.SECONDS)).isTrue();
        change.apply();
      } finally {
        resume.countDown();
      }
      return staleLogin.get(15, TimeUnit.SECONDS);
    }
  }

  private static void assertRejected(Reply login) {
    assertThat(login.status()).isEqualTo(401);
    assertThat(login.body().has("token")).isFalse();
  }

  private Reply me(String token) throws Exception {
    return api.call("GET", ME, token, null);
  }

  private int version(Account account) {
    return jdbc.queryForObject(
        "SELECT credential_version FROM users WHERE id = ?", Integer.class, account.id());
  }

  @FunctionalInterface
  private interface Change {
    void apply() throws Exception;
  }
}
