package vn.signa.identity;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import vn.signa.support.IntegrationTest;

/** Tài khoản và phiên qua HTTP thật với MySQL, Redis (FR-01, FR-30, NFR-10, IT-B10). */
@ExtendWith(OutputCaptureExtension.class)
class AuthIT extends IntegrationTest {

  private static final String PASSWORD = "mat-khau-it-" + UUID.randomUUID();

  private final HttpClient http = HttpClient.newHttpClient();

  @LocalServerPort int port;
  @Autowired JsonMapper json;
  @Autowired JdbcTemplate jdbc;
  @Autowired StringRedisTemplate redis;

  record Reply(int status, JsonNode body, HttpResponse<String> raw) {}

  @Test
  void registerLoginMeLogout() throws Exception {
    String email = newEmail();
    Reply registered = register(email, PASSWORD);
    assertThat(registered.status()).isEqualTo(201);
    assertThat(registered.body().get("role").asString()).isEqualTo("USER");

    assertThat(register(email.toUpperCase(), PASSWORD).status()).isEqualTo(409);

    Reply login = login("  " + email.toUpperCase() + " ", PASSWORD, "MOBILE").reply();
    assertThat(login.status()).isEqualTo(200);
    String token = login.body().get("token").asString();
    assertThat(login.body().get("user").get("permissions").toString())
        .contains("submission:create");
    assertThat(
            jdbc.queryForObject(
                "SELECT last_login_at IS NOT NULL FROM users WHERE email = ?",
                Boolean.class,
                email))
        .isTrue();

    Reply me = call("GET", "/api/v1/me", token, null);
    assertThat(me.status()).isEqualTo(200);
    assertThat(me.body().get("email").asString()).isEqualTo(email);
    assertThat(me.body().has("user")).isFalse();

    Reply renamed = call("PATCH", "/api/v1/me", token, "{\"displayName\":\"Tên mới\"}");
    assertThat(renamed.body().get("displayName").asString()).isEqualTo("Tên mới");

    assertThat(call("POST", "/api/v1/auth/logout", token, null).status()).isEqualTo(204);
    Reply after = call("GET", "/api/v1/me", token, null);
    assertThat(after.status()).isEqualTo(401);
    assertThat(after.body().get("code").asString()).isEqualTo("UNAUTHENTICATED");
  }

  @Test
  void redisKeepsOnlyTokenHash() throws Exception {
    String email = newEmail();
    register(email, PASSWORD);
    String token = login(email, PASSWORD, "MOBILE").token();
    String hash = sha256(token);

    String userId = redis.<String, String>opsForHash().get("session:" + hash, "userId");
    assertThat(userId).isNotNull();
    assertThat(redis.opsForHash().entries("session:" + hash))
        .containsKeys("userId", "role", "client", "createdAt");
    assertThat(redis.getExpire("session:" + hash)).isPositive();
    assertThat(redis.opsForSet().members("user-sessions:" + userId)).contains(hash);
    assertThat(redis.hasKey("session:" + token)).isFalse();
    Set<String> keys = redis.keys("*");
    assertThat(keys).noneMatch(k -> k.contains(token));
  }

  @Test
  void oldSessionIsRejectedAfterPasswordChange() throws Exception {
    String email = newEmail();
    register(email, PASSWORD);
    String current = login(email, PASSWORD, "MOBILE").token();
    String other = login(email, PASSWORD, "MOBILE").token();

    String wrong = "{\"currentPassword\":\"sai-mat-khau\",\"newPassword\":\"mat-khau-moi-1\"}";
    assertThat(call("PUT", "/api/v1/me/password", current, wrong).status()).isEqualTo(422);

    String body = "{\"currentPassword\":\"" + PASSWORD + "\",\"newPassword\":\"mat-khau-moi-1\"}";
    assertThat(call("PUT", "/api/v1/me/password", current, body).status()).isEqualTo(204);

    // IT-B10
    assertThat(call("GET", "/api/v1/me", other, null).status()).isEqualTo(401);
    assertThat(call("GET", "/api/v1/me", current, null).status()).isEqualTo(200);
    assertThat(login(email, PASSWORD, "MOBILE").reply().status()).isEqualTo(401);
    assertThat(login(email, "mat-khau-moi-1", "MOBILE").reply().status()).isEqualTo(200);
  }

  @Test
  void lockedAccountCannotSignIn() throws Exception {
    String email = newEmail();
    register(email, PASSWORD);
    jdbc.update("UPDATE users SET status = 'LOCKED' WHERE email = ?", email);

    Reply reply = login(email, PASSWORD, "MOBILE").reply();
    assertThat(reply.status()).isEqualTo(403);
    assertThat(reply.body().get("code").asString()).isEqualTo("FORBIDDEN");
    assertThat(login(email, "sai-mat-khau", "MOBILE").reply().status()).isEqualTo(401);
  }

  @Test
  void webLoginNeedsReviewPermission() throws Exception {
    String email = newEmail();
    register(email, PASSWORD);
    assertThat(login(email, PASSWORD, "WEB").reply().status()).isEqualTo(403);

    jdbc.update("UPDATE users SET role_code = 'MODERATOR' WHERE email = ?", email);
    Reply web = login(email, PASSWORD, "WEB").reply();
    assertThat(web.status()).isEqualTo(200);
    assertThat(web.body().get("user").get("permissions").toString()).contains("review:decide");
  }

  @Test
  void eleventhWrongAttemptIsRateLimited() throws Exception {
    String email = newEmail();
    register(email, PASSWORD);
    for (int i = 0; i < 10; i++) {
      Reply wrong = login(email, "sai-mat-khau", "MOBILE").reply();
      assertThat(wrong.status()).isEqualTo(401);
      assertThat(wrong.body().get("message").asString())
          .isEqualTo("Email hoặc mật khẩu không đúng");
    }

    Reply limited = login(email, PASSWORD, "MOBILE").reply();
    assertThat(limited.status()).isEqualTo(429);
    assertThat(limited.raw().headers().firstValue("Retry-After")).isPresent();
  }

  @Test
  void unknownEmailLooksLikeWrongPassword() throws Exception {
    Reply unknown = login(newEmail(), PASSWORD, "MOBILE").reply();
    assertThat(unknown.status()).isEqualTo(401);
    assertThat(unknown.body().get("message").asString())
        .isEqualTo("Email hoặc mật khẩu không đúng");
  }

  @Test
  void missingWrongOrRevokedTokenIs401() throws Exception {
    String email = newEmail();
    register(email, PASSWORD);
    String revoked = login(email, PASSWORD, "MOBILE").token();
    call("POST", "/api/v1/auth/logout", revoked, null);

    for (String token : new String[] {null, "sai", "a".repeat(43), revoked, revoked + "x"}) {
      Reply reply = call("GET", "/api/v1/me", token, null);
      assertThat(reply.status()).as(String.valueOf(token)).isEqualTo(401);
      assertThat(reply.body().get("code").asString()).isEqualTo("UNAUTHENTICATED");
      assertThat(reply.body().get("requestId").asString()).isNotBlank();
    }
  }

  @Test
  void passwordsAndTokensNeverReachTheLog(CapturedOutput output) throws Exception {
    String email = newEmail();
    String password = "log-secret-" + UUID.randomUUID();
    String newPassword = "log-secret-new-" + UUID.randomUUID();
    register(email, password);
    login(email, "log-secret-wrong-1", "MOBILE");
    String token = login(email, password, "MOBILE").token();
    call("GET", "/api/v1/me", token, null);
    call(
        "PUT",
        "/api/v1/me/password",
        token,
        "{\"currentPassword\":\"" + password + "\",\"newPassword\":\"" + newPassword + "\"}");
    call("POST", "/api/v1/auth/logout", token, null);

    assertThat(output.getAll())
        .doesNotContain(password)
        .doesNotContain(newPassword)
        .doesNotContain("log-secret-wrong-1")
        .doesNotContain(token)
        .doesNotContain("Using generated security password");
  }

  // ---- trợ giúp ----

  record Login(Reply reply) {
    String token() {
      assertThat(reply.status()).as(reply.raw().body()).isEqualTo(200);
      return reply.body().get("token").asString();
    }
  }

  private static String newEmail() {
    return "it-" + UUID.randomUUID() + "@signa.vn";
  }

  private Reply register(String email, String password) throws Exception {
    return call(
        "POST",
        "/api/v1/auth/register",
        null,
        json.writeValueAsString(
            java.util.Map.of("email", email, "password", password, "displayName", "Người thử")));
  }

  private Login login(String email, String password, String client) throws Exception {
    return new Login(
        call(
            "POST",
            "/api/v1/auth/login",
            null,
            json.writeValueAsString(
                java.util.Map.of("email", email, "password", password, "client", client))));
  }

  private Reply call(String method, String path, String token, String body)
      throws IOException, InterruptedException {
    HttpRequest.Builder request =
        HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
            .method(
                method,
                body == null
                    ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofString(body));
    if (body != null) {
      request.header("Content-Type", "application/json");
    }
    if (token != null) {
      request.header("Authorization", "Bearer " + token);
    }
    HttpResponse<String> response =
        http.send(request.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    JsonNode parsed = response.body().isEmpty() ? null : json.readTree(response.body());
    return new Reply(response.statusCode(), parsed, response);
  }

  private static String sha256(String token) throws Exception {
    return HexFormat.of()
        .formatHex(
            MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.US_ASCII)));
  }
}
