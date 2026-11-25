package vn.signa.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import vn.signa.support.IntegrationTest;

/** FR-13 qua HTTP thật với Redis, MySQL và mail sender giả. */
class PasswordResetIT extends IntegrationTest {

  private static final String AUTH = "/api/v1/auth";
  private static final String ME = "/api/v1/me";
  private static final String OLD_PASSWORD = "it-pass-password-1";
  private static final String NEW_PASSWORD = "it-pass-password-2";
  private static final String FORGOT = AUTH + "/password/forgot";
  private static final String RESET = AUTH + "/password/reset";
  private static final Pattern CODE = Pattern.compile("\\b[0-9]{6}\\b");

  private final HttpClient http = HttpClient.newHttpClient();
  private final BlockingQueue<SimpleMailMessage> inbox = new LinkedBlockingQueue<>();

  @LocalServerPort int port;
  @Autowired JsonMapper json;
  @Autowired StringRedisTemplate redis;
  @Autowired JdbcTemplate jdbc;

  @MockitoBean(name = "otpMailSender")
  JavaMailSender mail;

  @BeforeEach
  void captureMail() {
    doAnswer(
            invocation -> {
              inbox.add(invocation.getArgument(0));
              return null;
            })
        .when(mail)
        .send(any(SimpleMailMessage.class));
  }

  @Test
  void forgotAndResetChangePasswordAndRevokeExistingSessions() throws Exception {
    String email = register();
    String first = token(login(email, OLD_PASSWORD));
    String second = token(login(email, OLD_PASSWORD));

    HttpResponse<String> accepted = forgot("  " + email.toUpperCase(java.util.Locale.ROOT) + "  ");
    assertThat(accepted.statusCode()).isEqualTo(202);
    assertThat(accepted.body()).isEmpty();
    String code = receiveCode(email);
    String key = otpKey(email);
    assertThat(redis.opsForHash().entries(key))
        .containsEntry("attempts", "0")
        .hasEntrySatisfying(
            "codeHash", hash -> assertThat(hash.toString()).matches("[0-9a-f]{64}"));

    HttpResponse<String> reset = reset(email, code);
    assertThat(reset.statusCode()).isEqualTo(204);
    assertThat(reset.body()).isEmpty();
    assertThat(redis.hasKey(key)).isFalse();
    String passwordHash =
        jdbc.queryForObject("SELECT password_hash FROM users WHERE email = ?", String.class, email);
    assertThat(passwordHash).startsWith("$2a$12$");
    assertThat(getMe(first).statusCode()).isEqualTo(401);
    assertThat(getMe(second).statusCode()).isEqualTo(401);
    assertThat(login(email, OLD_PASSWORD).statusCode()).isEqualTo(401);
    assertThat(getMe(token(login(email, NEW_PASSWORD))).statusCode()).isEqualTo(200);
    invalidCode(reset(email, code));
  }

  @Test
  void fourthSendIsRateLimitedForActiveAndUnknownAddresses() throws Exception {
    String active = register();
    String unknown = newEmail();
    for (int i = 0; i < 3; i++) {
      var activeReply = forgot(active);
      var unknownReply = forgot(unknown);
      assertThat(activeReply.statusCode()).isEqualTo(202);
      assertThat(unknownReply.statusCode()).isEqualTo(202);
      assertThat(activeReply.body()).isEmpty();
      assertThat(unknownReply.body()).isEmpty();
      receiveCode(active);
    }
    for (String email : new String[] {active, unknown}) {
      var limited = forgot(email);
      assertThat(limited.statusCode()).isEqualTo(429);
      assertThat(json.readTree(limited.body()).path("code").asString()).isEqualTo("RATE_LIMITED");
      assertThat(Integer.parseInt(limited.headers().firstValue("Retry-After").orElseThrow()))
          .isBetween(1, 900);
    }
    assertThat(redis.hasKey(otpKey(unknown))).isFalse();
    assertThat(inbox.poll(500, TimeUnit.MILLISECONDS)).isNull();
  }

  @Test
  void otpExpiresAfterTenMinutesAccordingToRedisTtl() throws Exception {
    String email = register();
    assertThat(forgot(email).statusCode()).isEqualTo(202);
    String code = receiveCode(email);
    String key = otpKey(email);

    assertThat(redis.getExpire(key, TimeUnit.MILLISECONDS)).isBetween(590_000L, 600_000L);
    // Rút ngắn thời gian chờ sau khi đã đối chiếu TTL thật được cấp.
    assertThat(redis.expire(key, Duration.ofMillis(100))).isTrue();
    await().atMost(Duration.ofSeconds(5)).until(() -> !redis.hasKey(key));
    invalidCode(reset(email, code));
    assertThat(login(email, OLD_PASSWORD).statusCode()).isEqualTo(200);
  }

  @Test
  void resendReplacesCodeAndFifthWrongAttemptDeletesIt() throws Exception {
    String email = register();
    assertThat(forgot(email).statusCode()).isEqualTo(202);
    String first = receiveCode(email);
    invalidCode(reset(email, "sai"));

    assertThat(forgot(email).statusCode()).isEqualTo(202);
    String replacement = receiveCode(email);
    assertThat(replacement).isNotEqualTo(first);
    String key = otpKey(email);
    assertThat(redis.opsForHash().get(key, "attempts")).isEqualTo("0");

    for (int failures = 1; failures < 5; failures++) {
      invalidCode(reset(email, first));
      assertThat(redis.opsForHash().get(key, "attempts")).isEqualTo(Integer.toString(failures));
    }
    invalidCode(reset(email, first));
    assertThat(redis.hasKey(key)).isFalse();
    invalidCode(reset(email, replacement));
  }

  @Test
  void concurrentResetsCanOnlyConsumeCodeOnce() throws Exception {
    String email = register();
    assertThat(forgot(email).statusCode()).isEqualTo(202);
    String code = receiveCode(email);
    var payload = Map.of("email", email, "code", code, "newPassword", NEW_PASSWORD);
    var first = http.sendAsync(postRequest(RESET, payload), HttpResponse.BodyHandlers.ofString());
    var second = http.sendAsync(postRequest(RESET, payload), HttpResponse.BodyHandlers.ofString());

    assertThat(
            new int[] {
              first.get(10, TimeUnit.SECONDS).statusCode(),
              second.get(10, TimeUnit.SECONDS).statusCode()
            })
        .containsExactlyInAnyOrder(204, 422);
  }

  private String register() throws Exception {
    String email = newEmail();
    assertThat(
            post(
                    AUTH + "/register",
                    Map.of("email", email, "password", OLD_PASSWORD, "displayName", "Người thử"))
                .statusCode())
        .isEqualTo(201);
    return email;
  }

  private static String newEmail() {
    return "otp-" + UUID.randomUUID() + "@signa.vn";
  }

  private HttpResponse<String> forgot(String email) throws Exception {
    return post(FORGOT, Map.of("email", email));
  }

  private HttpResponse<String> reset(String email, String code) throws Exception {
    return post(RESET, Map.of("email", email, "code", code, "newPassword", NEW_PASSWORD));
  }

  private HttpResponse<String> login(String email, String password) throws Exception {
    return post(AUTH + "/login", Map.of("email", email, "password", password, "client", "MOBILE"));
  }

  private HttpResponse<String> getMe(String token) throws Exception {
    HttpRequest request =
        HttpRequest.newBuilder(uri(ME)).header("Authorization", "Bearer " + token).GET().build();
    return http.send(request, HttpResponse.BodyHandlers.ofString());
  }

  private HttpResponse<String> post(String path, Map<String, String> body) throws Exception {
    return http.send(postRequest(path, body), HttpResponse.BodyHandlers.ofString());
  }

  private HttpRequest postRequest(String path, Map<String, String> body) {
    return HttpRequest.newBuilder(uri(path))
        .header("Content-Type", "application/json")
        .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)))
        .build();
  }

  private URI uri(String path) {
    return URI.create("http://localhost:" + port + path);
  }

  private String receiveCode(String email) throws InterruptedException {
    SimpleMailMessage message = inbox.poll(10, TimeUnit.SECONDS);
    assertThat(message).isNotNull();
    assertThat(message.getTo()).containsExactly(email);
    assertThat(message.getSubject()).isEqualTo("Mã đặt lại mật khẩu Signa");
    assertThat(message.getText()).contains("hiệu lực 10 phút");
    var code = CODE.matcher(message.getText());
    assertThat(code.find()).isTrue();
    return code.group();
  }

  private String token(HttpResponse<String> login) {
    assertThat(login.statusCode()).isEqualTo(200);
    return json.readTree(login.body()).path("token").asString();
  }

  private void invalidCode(HttpResponse<String> response) {
    assertThat(response.statusCode()).isEqualTo(422);
    JsonNode error = json.readTree(response.body());
    assertThat(error.path("code").asString()).isEqualTo("BUSINESS_RULE");
    assertThat(error.path("message").asString())
        .isEqualTo("Mã đặt lại mật khẩu không hợp lệ hoặc đã hết hạn");
  }

  private static String otpKey(String email) throws Exception {
    byte[] hash =
        MessageDigest.getInstance("SHA-256").digest(email.getBytes(StandardCharsets.UTF_8));
    return "otp:" + HexFormat.of().formatHex(hash);
  }
}
