package vn.signa.catalog;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static vn.signa.shared.storage.Bucket.SIGN_ICONS;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import vn.signa.shared.storage.ObjectStorage;
import vn.signa.support.IntegrationTest;

/** FR-35 qua HTTP thật, MySQL, Redis và MinIO. */
class CatalogAdminIT extends IntegrationTest {

  private static final String CATEGORIES = "/api/v1/admin/sign-categories";
  private static final String TYPES = "/api/v1/admin/sign-types";
  private static final String PUBLIC_TYPES = "/api/v1/sign-types";
  private static final byte[] PNG =
      Base64.getDecoder()
          .decode(
              "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jM1sAAAAASUVORK5CYII=");

  private final HttpClient http = HttpClient.newHttpClient();

  @LocalServerPort int port;
  @Autowired JsonMapper json;
  @Autowired JdbcTemplate jdbc;
  @Autowired ObjectStorage storage;

  record Session(String userId, String token) {}

  @Test
  void createsEditsAndDeactivatesWithUserHistoryAndCacheEviction() throws Exception {
    Session admin = signIn("ADMIN");
    String category = "CAT_" + suffix();
    String nextCategory = category + "_2";
    String code = "IT." + suffix();
    String path = TYPES + "/" + code;
    String categoryInput =
        json.writeValueAsString(
            Map.of(
                "code",
                category.toLowerCase(java.util.Locale.ROOT),
                "name",
                "Nhóm thử",
                "description",
                "Mô tả cũ",
                "sortOrder",
                20));

    JsonNode createdCategory = change(admin, "POST", CATEGORIES, categoryInput, 201);
    assertThat(createdCategory.get("code").asString()).isEqualTo(category);
    assertStatus(call("POST", CATEGORIES, admin.token(), categoryInput), 409);
    JsonNode updatedCategory =
        change(
            admin,
            "PATCH",
            CATEGORIES + "/" + category,
            "{\"description\":null,\"sortOrder\":21}",
            200);
    assertThat(updatedCategory.get("description").isNull()).isTrue();
    assertThat(updatedCategory.get("sortOrder").intValue()).isEqualTo(21);
    createCategory(admin, nextCategory);

    String typeInput = typeInput(code, category);
    JsonNode created = change(admin, "POST", TYPES, typeInput, 201);
    assertThat(created.get("active").asBoolean()).isTrue();
    assertThat(created.get("recognizable").asBoolean()).isFalse();
    assertThat(created.get("updatedAt").asString()).isNotBlank();
    assertThat(
            jdbc.queryForObject(
                "SELECT label_id FROM sign_types WHERE code = ?", Integer.class, code))
        .isNull();
    assertStatus(call("POST", TYPES, admin.token(), typeInput), 409);

    JsonNode edited =
        change(
            admin,
            "PATCH",
            path,
            json.writeValueAsString(
                Map.of(
                    "name",
                    "Biển đã sửa",
                    "description",
                    "Ý nghĩa mới",
                    "categoryCode",
                    nextCategory,
                    "speedKmh",
                    50)),
            200);
    assertThat(edited.get("categoryCode").asString()).isEqualTo(nextCategory);
    assertThat(edited.get("name").asString()).isEqualTo("Biển đã sửa");
    assertThat(edited.get("description").asString()).isEqualTo("Ý nghĩa mới");
    assertThat(edited.get("speedKmh").intValue()).isEqualTo(50);

    JsonNode deactivated =
        change(admin, "PATCH", path, "{\"active\":false,\"speedKmh\":null}", 200);
    assertThat(deactivated.get("active").asBoolean()).isFalse();
    assertThat(deactivated.get("speedKmh").isNull()).isTrue();
    assertThat(deactivated.get("description").asString()).isEqualTo("Ý nghĩa mới");
    assertThat(assertStatus(call("GET", TYPES, admin.token(), null), 200)).contains(code);
    assertThat(assertStatus(call("GET", TYPES + "?active=false", admin.token(), null), 200))
        .contains(code);
    assertThat(assertStatus(call("GET", TYPES + "?active=true", admin.token(), null), 200))
        .doesNotContain(code);
    assertStatus(call("GET", PUBLIC_TYPES + "/" + code, null, null), 404);

    List<Map<String, Object>> categoryHistory = history("SIGN_CATEGORY", category, admin.userId());
    assertThat(categoryHistory)
        .extracting(row -> row.get("action"))
        .containsExactly("CREATED", "UPDATED");
    assertThat(value(categoryHistory.get(1), "before_value").get("sortOrder").intValue())
        .isEqualTo(20);
    assertThat(value(categoryHistory.get(1), "after_value").get("description").isNull()).isTrue();

    List<Map<String, Object>> typeHistory = history("SIGN_TYPE", code, admin.userId());
    assertThat(typeHistory)
        .extracting(row -> row.get("action"))
        .containsExactly("CREATED", "UPDATED", "DEACTIVATED");
    assertThat(typeHistory.getFirst().get("before_value")).isNull();
    assertThat(value(typeHistory.get(1), "before_value").get("categoryCode").asString())
        .isEqualTo(category);
    assertThat(value(typeHistory.get(1), "after_value").get("categoryCode").asString())
        .isEqualTo(nextCategory);
    assertThat(value(typeHistory.get(2), "before_value").get("active").asBoolean()).isTrue();
    assertThat(value(typeHistory.get(2), "after_value").get("active").asBoolean()).isFalse();
  }

  @Test
  void moderatorCannotReadOrChangeAdminCatalog() throws Exception {
    Session moderator = signIn("MODERATOR");
    assertStatus(call("GET", TYPES, moderator.token(), null), 403);
    String input = "{\"code\":\"CAT_" + suffix() + "\",\"name\":\"Nhóm thử\",\"sortOrder\":1}";
    String body = assertStatus(call("POST", CATEGORIES, moderator.token(), input), 403);
    assertThat(json.readTree(body).get("code").asString()).isEqualTo("FORBIDDEN");
  }

  @Test
  void rejectsSpoofedIconAndReplacesStoredIconByActualFormat() throws Exception {
    Session admin = signIn("ADMIN");
    String category = "CAT_" + suffix();
    String code = "IT." + suffix();
    createCategory(admin, category);
    change(admin, "POST", TYPES, typeInput(code, category), 201);

    String fake =
        assertStatus(upload(admin, code, "fake.png", "image/png", "fake".getBytes(UTF_8)), 415);
    assertThat(json.readTree(fake).get("code").asString()).isEqualTo("UNSUPPORTED_MEDIA_TYPE");
    assertThat(storage.list(SIGN_ICONS, code)).isEmpty();

    assertStatus(upload(admin, code, "one.png", "image/png", PNG), 204);
    String firstKey = currentIconKey(code);
    assertThat(firstKey).startsWith(code + "-").endsWith(".png");
    assertThat(storage.exists(SIGN_ICONS, firstKey)).isTrue();

    assertStatus(upload(admin, code, "another.png", "image/png", PNG), 204);
    String secondKey = currentIconKey(code);
    assertThat(secondKey).startsWith(code + "-").endsWith(".png").isNotEqualTo(firstKey);
    assertThat(storage.exists(SIGN_ICONS, firstKey)).isFalse();

    var jpeg = new ByteArrayOutputStream();
    assertThat(ImageIO.write(new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB), "jpeg", jpeg))
        .isTrue();
    assertStatus(upload(admin, code, "two.jpg", "image/jpeg", jpeg.toByteArray()), 204);
    String jpegKey = currentIconKey(code);
    assertThat(jpegKey).startsWith(code + "-").endsWith(".jpg");
    assertThat(storage.exists(SIGN_ICONS, jpegKey)).isTrue();
    assertThat(storage.exists(SIGN_ICONS, secondKey)).isFalse();
    String detail = assertStatus(call("GET", PUBLIC_TYPES + "/" + code, null, null), 200);
    assertThat(json.readTree(detail).get("iconUrl").asString())
        .isEqualTo("/icons/" + jpegKey);

    List<Map<String, Object>> history = history("SIGN_TYPE", code, admin.userId());
    assertThat(history)
        .extracting(row -> row.get("action"))
        .containsExactly("CREATED", "UPDATED", "UPDATED", "UPDATED");
    assertThat(value(history.getLast(), "before_value").get("iconKey").asString())
        .isEqualTo(secondKey);
    assertThat(value(history.getLast(), "after_value").get("iconKey").asString())
        .isEqualTo(jpegKey);
  }

  private String currentIconKey(String code) {
    return jdbc.queryForObject("SELECT icon_key FROM sign_types WHERE code = ?", String.class, code);
  }

  private Session signIn(String role) throws Exception {
    String email = UUID.randomUUID() + "@example.vn";
    String password = "mat-khau-it-35";
    String registration =
        json.writeValueAsString(
            Map.of("email", email, "password", password, "displayName", "Tài khoản danh mục"));
    String profile = assertStatus(call("POST", "/api/v1/auth/register", null, registration), 201);
    String userId = json.readTree(profile).get("id").asString();
    jdbc.update("UPDATE users SET role_code = ? WHERE id = ?", role, userId);
    String credentials =
        json.writeValueAsString(Map.of("email", email, "password", password, "client", "WEB"));
    String login = assertStatus(call("POST", "/api/v1/auth/login", null, credentials), 200);
    return new Session(userId, json.readTree(login).get("token").asString());
  }

  private void createCategory(Session admin, String code) throws Exception {
    change(
        admin,
        "POST",
        CATEGORIES,
        json.writeValueAsString(Map.of("code", code, "name", "Nhóm thử", "sortOrder", 1)),
        201);
  }

  private String typeInput(String code, String category) {
    return json.writeValueAsString(
        Map.of(
            "code",
            code,
            "categoryCode",
            category,
            "name",
            "Biển thử",
            "speedKmh",
            40,
            "recognizable",
            true));
  }

  private JsonNode change(Session admin, String method, String path, String body, int status)
      throws Exception {
    String result = assertStatus(call(method, path, admin.token(), body), status);
    return json.readTree(result);
  }

  private List<Map<String, Object>> history(String entity, String code, String actor) {
    List<Map<String, Object>> result =
        jdbc.queryForList(
            """
            SELECT action, actor_type, actor_id, before_value, after_value FROM audit_logs
             WHERE entity_type = ? AND entity_id = ? ORDER BY id
            """,
            entity,
            code);
    assertThat(result)
        .allSatisfy(row ->
            assertThat(row).containsEntry("actor_type", "USER").containsEntry("actor_id", actor));
    return result;
  }

  private JsonNode value(Map<String, Object> row, String column) {
    return json.readTree(row.get(column).toString());
  }

  private HttpResponse<String> call(String method, String path, String token, String body)
      throws Exception {
    HttpRequest.Builder request = HttpRequest.newBuilder(uri(path));
    if (token != null) request.header("Authorization", "Bearer " + token);
    if (body != null) request.header("Content-Type", "application/json");
    return http.send(
        request
            .method(
                method,
                body == null
                    ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofString(body))
            .build(),
        HttpResponse.BodyHandlers.ofString());
  }

  private HttpResponse<String> upload(
      Session admin, String code, String name, String mime, byte[] bytes) throws Exception {
    String boundary = UUID.randomUUID().toString();
    byte[] prefix =
        ("--"
                + boundary
                + "\r\n"
                + "Content-Disposition: form-data; name=\"icon\"; filename=\""
                + name
                + "\"\r\n"
                + "Content-Type: "
                + mime
                + "\r\n\r\n")
            .getBytes(UTF_8);
    byte[] suffix = ("\r\n--" + boundary + "--\r\n").getBytes(UTF_8);
    HttpRequest request =
        HttpRequest.newBuilder(uri(TYPES + "/" + code + "/icon"))
            .header("Authorization", "Bearer " + admin.token())
            .header("Content-Type", "multipart/form-data; boundary=" + boundary)
            .PUT(HttpRequest.BodyPublishers.ofByteArrays(List.of(prefix, bytes, suffix)))
            .build();
    return http.send(request, HttpResponse.BodyHandlers.ofString());
  }

  private URI uri(String path) {
    return URI.create("http://localhost:" + port + path);
  }

  private static String assertStatus(HttpResponse<String> response, int expected) {
    assertThat(response.statusCode())
        .as("HTTP %s: %s", response.uri().getPath(), response.body())
        .isEqualTo(expected);
    return response.body();
  }

  private static String suffix() {
    return UUID.randomUUID().toString().substring(0, 8).toUpperCase(java.util.Locale.ROOT);
  }
}
