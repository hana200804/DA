package vn.signa.sign.internal.feedback;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static vn.signa.shared.storage.Bucket.REPORT_PHOTOS;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Base64;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import vn.signa.shared.storage.ObjectStorage;
import vn.signa.support.IntegrationTest;

/** Kiểm tra hợp đồng HTTP, MySQL, Redis và MinIO bằng Testcontainers trên CI. */
class FeedbackIT extends IntegrationTest {

  private static final String API = "/api/v1";
  private static final String ADMIN_REPORTS = API + "/admin/reports";
  private static final String MINE = API + "/me/reports";
  private static final byte[] PNG =
      Base64.getDecoder()
          .decode(
              "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAACklEQVR4nGMAAQAABQABDQottAAAAABJRU5ErkJggg==");
  private final HttpClient http = HttpClient.newHttpClient();

  @LocalServerPort int port;
  @Autowired JsonMapper json;
  @Autowired JdbcTemplate jdbc;
  @Autowired ObjectStorage storage;

  @Test
  void itB11DuplicatePendingReturnsConflictAndRollsBackTheNewPhoto() throws Exception {
    var user = signIn("Người báo cáo", "USER");
    String path = signPath(sign("APPROVED")) + "/reports";
    String id = body(upload(path, user, "NOT_EXIST"), 201).get("id").asString();
    Set<String> before = photoKeys();

    assertThat(body(upload(path, user, "WRONG_TYPE"), 409).get("code").asString())
        .isEqualTo("CONFLICT");
    assertThat(photoKeys()).isEqualTo(before).contains(id + ".png");
  }

  @Test
  void submitterCannotRateTheirSign() throws Exception {
    var submitter = signIn("Người đề xuất", "USER");
    var sign = sign("APPROVED");
    jdbc.update(
        """
        INSERT INTO submissions
          (id, user_id, client_id, sign_id, sign_type_code, confidence, location, source,
           crop_key, model_name, model_version, label_map_version, captured_at, received_at, status)
        VALUES (?, ?, ?, ?, ?, 0.9, ST_GeomFromText('POINT(105 21)', 4326, 'axis-order=long-lat'),
                'CAMERA', 'test.jpg', 'yolov8n', 'it', 1, UTC_TIMESTAMP(3), UTC_TIMESTAMP(3), 'ACCEPTED')
        """,
        uuid(),
        submitter.id(),
        uuid(),
        sign.id(),
        sign.code());

    var response = request("PUT", signPath(sign) + "/rating", submitter, "{\"value\":\"CONFIRM\"}");
    assertThat(body(response, 422).get("code").asString()).isEqualTo("BUSINESS_RULE");
  }

  @Test
  void ratingDetailAndReviewFollowTheLatestVoteOfEachUser() throws Exception {
    var first = signIn("Người đánh giá 1", "USER");
    var second = signIn("Người đánh giá 2", "USER");
    var third = signIn("Người đánh giá 3", "USER");
    var sign = sign("APPROVED");
    String path = signPath(sign);
    assertThat(body(request("GET", path, null, null), 200).has("myRating")).isFalse();
    assertThat(body(request("GET", path, first, null), 200).get("myRating").isNull()).isTrue();

    rate(path, first, "CONFIRM");
    assertThat(body(request("GET", path, first, null), 200).get("myRating").asString())
        .isEqualTo("CONFIRM");
    rate(path, first, "DENY");
    rate(path, first, "DENY");
    rate(path, second, "DENY");
    assertThat(body(request("GET", path, first, null), 200).get("status").asString())
        .isEqualTo("APPROVED");
    rate(path, third, "DENY");

    var detail = body(request("GET", path, first, null), 200);
    assertThat(detail.get("myRating").asString()).isEqualTo("DENY");
    assertThat(detail.get("status").asString()).isEqualTo("UNDER_REVIEW");
    assertThat(detail.get("confirmCount").asInt()).isZero();
    assertThat(detail.get("denyCount").asInt()).isEqualTo(3);
    assertThat(
            jdbc.queryForMap(
                "SELECT version, deny_since_review FROM signs WHERE id = ?", sign.id()))
        .containsEntry("version", 5)
        .containsEntry("deny_since_review", 3);
    assertThat(
            jdbc.queryForList(
                "SELECT action FROM audit_logs WHERE entity_id = ?", String.class, sign.id()))
        .containsExactly("MARKED_UNDER_REVIEW");
  }

  @Test
  void reportWithPhotoStoresDetectedTypeAndAdminCanReadTheSignedUrl() throws Exception {
    var user = signIn("Người gửi ảnh", "USER");
    var moderator = signIn("Kiểm duyệt ảnh", "MODERATOR");
    var sign = sign("UNDER_REVIEW");
    var created = body(upload(signPath(sign) + "/reports", user, "WRONG_TYPE"), 201);
    String id = created.get("id").asString();
    assertThat(created.get("status").asString()).isEqualTo("PENDING");
    assertThat(storage.exists(REPORT_PHOTOS, id + ".png")).isTrue();

    var queue = body(request("GET", ADMIN_REPORTS + "?reason=WRONG_TYPE", moderator, null), 200);
    var group =
        queue.get("items").values().stream()
            .filter(item -> item.get("sign").get("id").asString().equals(sign.id()))
            .findFirst()
            .orElseThrow();
    assertThat(group.get("sign").get("signName").asString()).isEqualTo("Biển thử");
    var report = group.get("reports").get(0);
    assertThat(report.get("id").asString()).isEqualTo(id);
    assertThat(report.get("reporterName").asString()).isEqualTo("Người gửi ảnh");

    var photo =
        http.send(
            HttpRequest.newBuilder(URI.create(report.get("photoUrl").asString())).GET().build(),
            HttpResponse.BodyHandlers.ofByteArray());
    assertThat(photo.statusCode()).isEqualTo(200);
    assertThat(photo.headers().firstValue("Content-Type")).contains("image/png");
    assertThat(photo.body()).isEqualTo(PNG);
  }

  @Test
  void myReportsOnlyReturnsTheCallersReportsAndPaginates() throws Exception {
    var owner = signIn("Người báo cáo 1", "USER");
    var other = signIn("Người báo cáo 2", "USER");
    var sign = sign("APPROVED");
    String first = report(sign, owner, "NOT_EXIST");
    String second = report(sign("APPROVED"), owner, "WRONG_TYPE");
    String hidden = report(sign, other, "NOT_EXIST");

    var page = body(request("GET", MINE + "?page=0&size=1", owner, null), 200);
    assertThat(page.get("total").asInt()).isEqualTo(2);
    var item = page.get("items").get(0);
    assertThat(item.get("status").asString()).isEqualTo("PENDING");
    var next = body(request("GET", MINE + "?page=1&size=1", owner, null), 200);
    assertThat(Set.of(item.get("id").asString(), next.get("items").get(0).get("id").asString()))
        .containsExactlyInAnyOrder(first, second)
        .doesNotContain(hidden);
  }

  @Test
  void adminPaginationCountsSignsAndKeepsAllMatchingReportsTogether() throws Exception {
    var user = signIn("Người báo cáo", "USER");
    var other = signIn("Người báo cáo khác", "USER");
    var moderator = signIn("Người xử lý", "MODERATOR");
    var solo = sign("APPROVED");
    var shared = sign("APPROVED");
    report(solo, user, "WRONG_LOCATION");
    String first = report(shared, user, "WRONG_LOCATION");
    String second = report(shared, other, "WRONG_LOCATION");
    jdbc.update("UPDATE sign_reports SET created_at = '2000-01-01' WHERE sign_id = ?", solo.id());

    String path = ADMIN_REPORTS + "?reason=WRONG_LOCATION&size=1";
    var page = body(request("GET", path, moderator, null), 200);
    assertThat(page.get("total").asInt()).isEqualTo(2);
    assertThat(page.get("items").size()).isEqualTo(1);
    var group = page.get("items").get(0);
    assertThat(group.get("sign").get("id").asString()).isEqualTo(shared.id());
    assertThat(group.get("reports").values().stream().map(row -> row.get("id").asString()))
        .containsExactlyInAnyOrder(first, second);
    var next = body(request("GET", path + "&page=1", moderator, null), 200);
    assertThat(next.get("items").get(0).get("sign").get("id").asString()).isEqualTo(solo.id());
  }

  @Test
  void dismissPersistsResolutionAndHistoryAndAllowsAnotherReport() throws Exception {
    var owner = signIn("Người báo cáo", "USER");
    var moderator = signIn("Kiểm duyệt viên", "MODERATOR");
    var sign = sign("APPROVED");
    String id = report(sign, owner, "NOT_EXIST");
    String path = ADMIN_REPORTS + "/" + id + "/dismiss";
    String note = "{\"note\":\"Đã kiểm tra tại hiện trường\"}";

    assertThat(request("POST", path, moderator, note).statusCode()).isEqualTo(204);
    assertThat(body(request("POST", path, moderator, note), 409).get("code").asString())
        .isEqualTo("CONFLICT");
    var item = body(request("GET", MINE, owner, null), 200).get("items").get(0);
    assertThat(item.get("id").asString()).isEqualTo(id);
    assertThat(item.get("status").asString()).isEqualTo("DISMISSED");
    assertThat(item.get("resolution").isNull()).isTrue();
    assertThat(item.get("resolutionNote").asString()).isEqualTo("Đã kiểm tra tại hiện trường");
    assertThat(item.get("resolvedAt").isString()).isTrue();
    assertThat(
            jdbc.queryForObject(
                "SELECT resolved_by FROM sign_reports WHERE id = ?", String.class, id))
        .isEqualTo(moderator.id());
    assertThat(
            jdbc.queryForList(
                "SELECT actor_id FROM audit_logs WHERE entity_type = 'REPORT' AND entity_id = ? AND action = 'DISMISSED'",
                String.class,
                id))
        .containsExactly(moderator.id());
    assertThat(report(sign, owner, "NOT_EXIST")).isNotEqualTo(id);
  }

  private Sign sign(String status) {
    String suffix = uuid().substring(0, 8);
    String category = "TEST_" + suffix;
    String code = "P.102_" + suffix;
    String id = uuid();
    jdbc.update(
        "INSERT INTO sign_categories (code, name, sort_order) VALUES (?, 'Nhóm thử', 99)",
        category);
    jdbc.update(
        "INSERT INTO sign_types (code, category_code, name, created_at, updated_at) "
            + "VALUES (?, ?, 'Biển thử', UTC_TIMESTAMP(3), UTC_TIMESTAMP(3))",
        code,
        category);
    jdbc.update(
        """
        INSERT INTO signs
          (id, sign_type_code, category_code, location, status, confidence, moderator_boost,
           reviewed_at, created_at, updated_at)
        VALUES (?, ?, ?, ST_GeomFromText('POINT(105 21)', 4326, 'axis-order=long-lat'), ?, 0.9, 2,
                UTC_TIMESTAMP(3) - INTERVAL 1 DAY, UTC_TIMESTAMP(3), UTC_TIMESTAMP(3))
        """,
        id,
        code,
        category,
        status);
    jdbc.update(
        "INSERT INTO sign_code_weights (sign_id, sign_type_code, weight, submitter_count) "
            + "VALUES (?, ?, 4, 4)",
        id,
        code);
    return new Sign(id, code);
  }

  private Session signIn(String name, String role) throws Exception {
    String email = uuid() + "@example.vn";
    String password = "mat-khau-it-07";
    String register =
        json.writeValueAsString(Map.of("email", email, "password", password, "displayName", name));
    String id =
        body(request("POST", API + "/auth/register", null, register), 201).get("id").asString();
    if (!role.equals("USER")) jdbc.update("UPDATE users SET role_code = ? WHERE id = ?", role, id);
    String login =
        json.writeValueAsString(Map.of("email", email, "password", password, "client", "MOBILE"));
    String token =
        body(request("POST", API + "/auth/login", null, login), 200).get("token").asString();
    return new Session(id, token);
  }

  private String report(Sign sign, Session user, String reason) throws Exception {
    var response =
        request("POST", signPath(sign) + "/reports", user, "{\"reason\":\"" + reason + "\"}");
    return body(response, 201).get("id").asString();
  }

  private void rate(String path, Session user, String value) throws Exception {
    assertThat(request("PUT", path + "/rating", user, "{\"value\":\"" + value + "\"}").statusCode())
        .isEqualTo(204);
  }

  private HttpResponse<String> request(String method, String path, Session user, String data)
      throws Exception {
    var request = builder(path, user);
    if (data != null) request.header("Content-Type", "application/json");
    return http.send(
        request
            .method(
                method,
                data == null
                    ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofString(data))
            .build(),
        HttpResponse.BodyHandlers.ofString());
  }

  private HttpResponse<String> upload(String path, Session user, String reason) throws Exception {
    String boundary = uuid();
    String prefix =
        "--"
            + boundary
            + "\r\n"
            + "Content-Disposition: form-data; name=\"metadata\"\r\n"
            + "Content-Type: application/json\r\n\r\n"
            + "{\"reason\":\""
            + reason
            + "\"}\r\n--"
            + boundary
            + "\r\n"
            + "Content-Disposition: form-data; name=\"photo\"; filename=\"proof.txt\"\r\n"
            + "Content-Type: text/plain\r\n\r\n";
    String suffix = "\r\n--" + boundary + "--\r\n";
    var data =
        HttpRequest.BodyPublishers.ofByteArrays(
            java.util.List.of(prefix.getBytes(UTF_8), PNG, suffix.getBytes(UTF_8)));
    return http.send(
        builder(path, user)
            .header("Content-Type", "multipart/form-data; boundary=" + boundary)
            .POST(data)
            .build(),
        HttpResponse.BodyHandlers.ofString());
  }

  private HttpRequest.Builder builder(String path, Session user) {
    var builder = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path));
    return user == null ? builder : builder.header("Authorization", "Bearer " + user.token());
  }

  private JsonNode body(HttpResponse<String> response, int status) {
    assertThat(response.statusCode()).as("%s", response.body()).isEqualTo(status);
    return json.readTree(response.body());
  }

  private Set<String> photoKeys() {
    Set<String> keys = new HashSet<>();
    storage.list(REPORT_PHOTOS, "").forEach(photo -> keys.add(photo.key()));
    return keys;
  }

  private static String signPath(Sign sign) {
    return API + "/signs/" + sign.id();
  }

  private static String uuid() {
    return UUID.randomUUID().toString();
  }

  private record Sign(String id, String code) {}

  private record Session(String id, String token) {}
}
