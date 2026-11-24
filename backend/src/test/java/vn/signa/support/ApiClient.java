package vn.signa.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Gọi API thật trong test {@code *IT}: đăng ký, đăng nhập, gọi có token. Vai trò ban đầu đặt bằng
 * {@code UPDATE users} với {@code signa_app}, trước khi đăng nhập vì phiên giữ vai trò lúc tạo.
 */
public final class ApiClient {

  public static final String PASSWORD = "mat-khau-it-" + UUID.randomUUID();

  /** Kết quả một lời gọi; {@code body} là {@code null} khi response rỗng. */
  public record Reply(int status, JsonNode body, HttpResponse<String> raw) {}

  /** Tài khoản đã đăng nhập. */
  public record Account(String id, String email, String displayName, String token) {}

  private final HttpClient http = HttpClient.newHttpClient();
  private final int port;
  private final JsonMapper json;
  private final JdbcTemplate jdbc;

  public ApiClient(int port, JsonMapper json, JdbcTemplate jdbc) {
    this.port = port;
    this.json = json;
    this.jdbc = jdbc;
  }

  /** Đăng ký với email ngẫu nhiên, đặt vai trò rồi đăng nhập {@code MOBILE}. */
  public Account newAccount(String role) throws IOException, InterruptedException {
    String email = "it-" + UUID.randomUUID() + "@signa.vn";
    String displayName = "Người thử " + role + " " + email.substring(3, 11);
    Reply registered =
        call(
            "POST",
            "/api/v1/auth/register",
            null,
            json.writeValueAsString(
                Map.of("email", email, "password", PASSWORD, "displayName", displayName)));
    assertThat(registered.status()).as(registered.raw().body()).isEqualTo(201);
    String id = registered.body().get("id").asString();
    if (!role.equals("USER")) {
      jdbc.update("UPDATE users SET role_code = ? WHERE id = ?", role, id);
    }
    return new Account(id, email, displayName, login(email).body().get("token").asString());
  }

  public Reply login(String email) throws IOException, InterruptedException {
    return call(
        "POST",
        "/api/v1/auth/login",
        null,
        json.writeValueAsString(Map.of("email", email, "password", PASSWORD, "client", "MOBILE")));
  }

  public Reply call(String method, String path, String token, String body)
      throws IOException, InterruptedException {
    return call(method, path, token, body, null);
  }

  public Reply call(String method, String path, String token, String body, String requestId)
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
    if (requestId != null) {
      request.header("X-Request-Id", requestId);
    }
    HttpResponse<String> response =
        http.send(request.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    JsonNode parsed = response.body().isEmpty() ? null : json.readTree(response.body());
    return new Reply(response.statusCode(), parsed, response);
  }
}
