package vn.signa.sign.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import vn.signa.sign.clustering.GeoPoint;

/**
 * HTTP và dữ liệu mẫu cho IT phê duyệt. Mã nhóm, mã loại mang hậu tố ngẫu nhiên và toạ độ chọn ngẫu
 * nhiên giữa Đại Tây Dương, để không đụng dữ liệu của IT khác trong cùng CSDL ({@code signa_app}
 * không xoá được {@code signs}).
 */
final class ReviewItSupport {

  static final String ADMIN = "/api/v1/admin";

  private final HttpClient http = HttpClient.newHttpClient();
  private final int port;
  private final JsonMapper json;
  private final JdbcTemplate jdbc;
  private final String suffix =
      UUID.randomUUID().toString().replace("-", "").substring(0, 10).toUpperCase();

  ReviewItSupport(int port, JsonMapper json, JdbcTemplate jdbc) {
    this.port = port;
    this.json = json;
    this.jdbc = jdbc;
  }

  record Session(String id, String token) {}

  // ---------- tài khoản

  /** Tài khoản {@code MODERATOR} đăng nhập web. */
  Session moderator() throws Exception {
    String email = uuid() + "@example.vn";
    String password = "mat-khau-it-06";
    String register =
        json.writeValueAsString(
            Map.of("email", email, "password", password, "displayName", "Kiểm duyệt viên"));
    String id =
        body(send("POST", "/api/v1/auth/register", null, register), 201).get("id").asString();
    jdbc.update("UPDATE users SET role_code = 'MODERATOR' WHERE id = ?", id);
    String login =
        json.writeValueAsString(Map.of("email", email, "password", password, "client", "WEB"));
    String token =
        body(send("POST", "/api/v1/auth/login", null, login), 200).get("token").asString();
    return new Session(id, token);
  }

  /** Người gửi đề xuất, chèn thẳng vào {@code users} vì không cần đăng nhập. */
  String submitter() {
    String id = uuid();
    jdbc.update(
        "INSERT INTO users (id, email, password_hash, display_name, created_at, updated_at)"
            + " VALUES (?, ?, 'x', 'Người gửi', UTC_TIMESTAMP(3), UTC_TIMESTAMP(3))",
        id,
        id + "@example.vn");
    return id;
  }

  // ---------- danh mục, biển, đề xuất

  String category(String name) {
    String code = name + suffix;
    jdbc.update(
        "INSERT INTO sign_categories (code, name, sort_order) VALUES (?, 'Nhóm thử', 99)", code);
    return code;
  }

  String type(String name, String category) {
    String code = name + "." + suffix;
    jdbc.update(
        "INSERT INTO sign_types (code, category_code, name, created_at, updated_at)"
            + " VALUES (?, ?, ?, UTC_TIMESTAMP(3), UTC_TIMESTAMP(3))",
        code,
        category,
        "Loại " + name);
    return code;
  }

  /** Toạ độ ngẫu nhiên giữa biển, mỗi test một chỗ. */
  static GeoPoint somewhere() {
    ThreadLocalRandom random = ThreadLocalRandom.current();
    return new GeoPoint(random.nextDouble(-40, 40), random.nextDouble(-35, -15));
  }

  /** Dời {@code meters} mét về phía bắc. */
  static GeoPoint north(GeoPoint from, double meters) {
    return new GeoPoint(from.lat() + meters / 111_194.9, from.lng());
  }

  /** Chèn một biển bằng SQL, không có lịch sử. */
  String sign(
      String code,
      String category,
      GeoPoint at,
      String status,
      String confidence,
      String suggestion,
      Instant createdAt) {
    String id = uuid();
    jdbc.update(
        """
        INSERT INTO signs (id, sign_type_code, category_code, location, heading_deg, status,
                           confidence, suggestion, distinct_submitters, reviewed_at,
                           created_at, updated_at)
        VALUES (?, ?, ?, ST_GeomFromText(?, 4326, 'axis-order=long-lat'), 90, ?, ?, ?, 1, ?, ?, ?)
        """,
        id,
        code,
        category,
        point(at),
        status,
        new BigDecimal(confidence),
        suggestion,
        status.equals("PENDING") ? null : utc(createdAt),
        utc(createdAt),
        utc(createdAt));
    return id;
  }

  /** Thêm một đề xuất đã gom vào biển, kèm {@code W_c}. */
  String member(String sign, String user, String code, String weight, GeoPoint at) {
    String id = submission(user, code, at, sign, weight, "GROUPED");
    jdbc.update(
        """
        INSERT INTO sign_code_weights (sign_id, sign_type_code, weight, submitter_count)
        VALUES (?, ?, ?, 1) AS incoming
        ON DUPLICATE KEY UPDATE weight = sign_code_weights.weight + incoming.weight,
                                submitter_count = sign_code_weights.submitter_count + 1
        """,
        sign,
        code,
        new BigDecimal(weight));
    return id;
  }

  /** Đề xuất {@code RECEIVED} chờ worker gom cụm. */
  String received(String user, String code, GeoPoint at, String confidence) {
    String id = uuid();
    jdbc.update(
        """
        INSERT INTO submissions (id, user_id, client_id, sign_type_code, confidence, location,
                                 source, crop_key, model_name, model_version, label_map_version,
                                 captured_at, received_at, status)
        VALUES (?, ?, ?, ?, ?, ST_GeomFromText(?, 4326, 'axis-order=long-lat'), 'CAMERA', ?,
                'yolov8n', 'it', 1, UTC_TIMESTAMP(3), UTC_TIMESTAMP(3), 'RECEIVED')
        """,
        id,
        user,
        uuid(),
        code,
        new BigDecimal(confidence),
        point(at),
        id + ".jpg");
    return id;
  }

  private String submission(
      String user, String code, GeoPoint at, String sign, String weight, String status) {
    String id = uuid();
    jdbc.update(
        """
        INSERT INTO submissions (id, user_id, client_id, sign_id, sign_type_code, confidence,
                                 weight, location, source, crop_key, model_name, model_version,
                                 label_map_version, captured_at, received_at, status)
        VALUES (?, ?, ?, ?, ?, ?, ?, ST_GeomFromText(?, 4326, 'axis-order=long-lat'), 'CAMERA',
                ?, 'yolov8n', 'it', 1, UTC_TIMESTAMP(3), UTC_TIMESTAMP(3), ?)
        """,
        id,
        user,
        uuid(),
        sign,
        code,
        new BigDecimal(weight),
        new BigDecimal(weight),
        point(at),
        id + ".jpg",
        status);
    return id;
  }

  Map<String, Object> signRow(String id) {
    return jdbc.queryForMap(
        """
        SELECT status, sign_type_code, category_code, confidence, moderator_boost, version,
               reviewed_by, reviewed_at, location_locked, heading_deg, suggestion,
               ST_Latitude(location) AS lat, ST_Longitude(location) AS lng
        FROM signs WHERE id = ?
        """,
        id);
  }

  int version(String id) {
    return jdbc.queryForObject("SELECT version FROM signs WHERE id = ?", Integer.class, id);
  }

  Map<String, Object> submissionRow(String id) {
    return jdbc.queryForMap(
        "SELECT sign_id, status, reject_reason, weight FROM submissions WHERE id = ?", id);
  }

  BigDecimal weight(String sign, String code) {
    return jdbc.queryForObject(
        "SELECT weight FROM sign_code_weights WHERE sign_id = ? AND sign_type_code = ?",
        BigDecimal.class,
        sign,
        code);
  }

  List<Map<String, Object>> history(String sign) {
    return jdbc.queryForList(
        """
        SELECT action, actor_type, actor_id, before_value, after_value, reason, request_id
        FROM audit_logs WHERE entity_type = 'SIGN' AND entity_id = ? ORDER BY id
        """,
        sign);
  }

  JsonNode jsonColumn(Object value) {
    return value == null ? null : json.readTree(value.toString());
  }

  // ---------- HTTP

  HttpResponse<String> send(String method, String path, String token, Object data)
      throws Exception {
    String body =
        data == null ? null : data instanceof String s ? s : json.writeValueAsString(data);
    HttpRequest.Builder request =
        HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
            .timeout(Duration.ofSeconds(30));
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

  JsonNode body(HttpResponse<String> response, int expected) {
    assertStatus(response, expected);
    return json.readTree(response.body());
  }

  static void assertStatus(HttpResponse<String> response, int expected) {
    assertThat(response.statusCode())
        .as("HTTP %s %s: %s", response.request().method(), response.uri(), response.body())
        .isEqualTo(expected);
  }

  JsonNode detail(Session moderator, String id) throws Exception {
    return body(send("GET", ADMIN + "/signs/" + id, moderator.token(), null), 200);
  }

  HttpResponse<String> approve(Session moderator, String id, Map<String, ?> body) throws Exception {
    return send("POST", ADMIN + "/signs/" + id + "/approve", moderator.token(), body);
  }

  static String point(GeoPoint at) {
    return "POINT(" + at.lng() + " " + at.lat() + ")";
  }

  static LocalDateTime utc(Instant instant) {
    return LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
  }

  static String uuid() {
    return UUID.randomUUID().toString();
  }
}
