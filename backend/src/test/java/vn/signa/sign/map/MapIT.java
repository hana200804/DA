package vn.signa.sign.map;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import vn.signa.support.IntegrationTest;

/**
 * Bản đồ công khai trên MySQL thật (IT-B06, IT-B07, docs/06 §9). Mỗi ca dùng một vùng riêng giữa
 * Thái Bình Dương và mã nhóm, mã loại riêng, nên không lẫn với dữ liệu của test khác.
 */
class MapIT extends IntegrationTest {

  private final HttpClient http = HttpClient.newHttpClient();

  @LocalServerPort int port;
  @Autowired JdbcTemplate jdbc;
  @Autowired ObjectMapper json;

  private MapFixtures data;

  @BeforeEach
  void setUp() {
    data = new MapFixtures(jdbc);
  }

  /** IT-B06: chỉ biển trong {@code bbox} được trả. */
  @Test
  void onlySignsInsideBboxAreReturned() throws Exception {
    String cat = data.category("B6");
    String type = data.type("B6", cat);
    String in1 = data.sign(type, cat, -19.95, -139.95, "APPROVED");
    String in2 = data.sign(type, cat, -19.93, -139.92, "UNDER_REVIEW");
    data.sign(type, cat, -19.95, -139.85, "APPROVED"); // phía đông
    data.sign(type, cat, -19.85, -139.95, "APPROVED"); // phía bắc
    data.sign(type, cat, -19.95, -140.05, "APPROVED"); // phía tây
    data.sign(type, cat, -20.05, -139.95, "APPROVED"); // phía nam

    JsonNode body = ok("/api/v1/signs?bbox=-140,-20,-139.9,-19.9");

    assertThat(body.get("mode").asString()).isEqualTo("SIGNS");
    assertThat(ids(body)).containsExactlyInAnyOrder(in1, in2);
  }

  /** IT-B06 kèm lọc nhóm, mã, và cả hai (docs/07 §6: gửi cả hai thì phải khớp cả hai). */
  @Test
  void filtersByCategoryTypeOrBoth() throws Exception {
    String catA = data.category("FA");
    String catB = data.category("FB");
    String a1 = data.type("A1", catA);
    String a2 = data.type("A2", catA);
    String b1 = data.type("B1", catB);
    String signA1 = data.sign(a1, catA, -24.95, -134.95, "APPROVED");
    String signA2 = data.sign(a2, catA, -24.94, -134.94, "APPROVED");
    String signB1 = data.sign(b1, catB, -24.93, -134.93, "APPROVED");
    String box = "/api/v1/signs?bbox=-135,-25,-134.9,-24.9";

    assertThat(ids(ok(box))).containsExactlyInAnyOrder(signA1, signA2, signB1);
    assertThat(ids(ok(box + "&categories=" + catA))).containsExactlyInAnyOrder(signA1, signA2);
    assertThat(ids(ok(box + "&categories=" + catA + "," + catB)))
        .containsExactlyInAnyOrder(signA1, signA2, signB1);
    assertThat(ids(ok(box + "&signTypeCodes=" + q(b1)))).containsExactly(signB1);
    assertThat(ids(ok(box + "&signTypeCodes=" + q(a2 + "," + b1))))
        .containsExactlyInAnyOrder(signA2, signB1);
    assertThat(ids(ok(box + "&categories=" + catA + "&signTypeCodes=" + q(a2))))
        .containsExactly(signA2);
    assertThat(ids(ok(box + "&categories=" + catB + "&signTypeCodes=" + q(a1)))).isEmpty();
  }

  /** IT-B07: 600 biển khớp bộ lọc → gom ô, tổng số đếm đủ 600 dù truy vấn đầu dừng ở 501. */
  @Test
  void moreThan500SignsAreClusteredWithExactTotal() throws Exception {
    String cat = data.category("B7");
    String type = data.type("B7", cat);
    String other = data.type("B7X", cat);
    data.grid(type, cat, -9.99, -129.99, 30, 20, 0.03);
    data.grid(other, cat, -9.98, -129.98, 5, 5, 0.03); // cùng vùng, khác mã

    JsonNode body = ok("/api/v1/signs?bbox=-130,-10,-129,-9&zoom=10&signTypeCodes=" + q(type));

    assertThat(body.get("mode").asString()).isEqualTo("CLUSTERS");
    long total = 0;
    for (JsonNode cluster : body.get("items")) {
      long count = cluster.get("count").asLong();
      double lat = cluster.get("location").get("lat").asDouble();
      double lng = cluster.get("location").get("lng").asDouble();
      assertThat(count).isPositive();
      assertThat(lat).isBetween(-10.0, -9.0);
      assertThat(lng).isBetween(-130.0, -129.0);
      total += count;
    }
    assertThat(total).isEqualTo(600);
    assertThat(body.get("items").size()).isGreaterThan(1);
  }

  @Test
  void unpublishedSignsAreHiddenFromMapAndDetail() throws Exception {
    String cat = data.category("ST");
    String type = data.type("ST", cat);
    String approved = data.sign(type, cat, -29.95, -124.95, "APPROVED");
    String review = data.sign(type, cat, -29.94, -124.94, "UNDER_REVIEW");
    List<String> hidden =
        List.of(
            data.sign(type, cat, -29.93, -124.93, "PENDING"),
            data.sign(type, cat, -29.92, -124.92, "REJECTED"),
            data.sign(type, cat, -29.91, -124.91, "REMOVED"));

    assertThat(ids(ok("/api/v1/signs?bbox=-125,-30,-124.9,-29.9")))
        .containsExactlyInAnyOrder(approved, review);
    for (String id : hidden) {
      HttpResponse<String> response = get("/api/v1/signs/" + id);
      assertThat(response.statusCode()).as(id).isEqualTo(404);
      assertThat(json.readTree(response.body()).get("code").asString()).isEqualTo("NOT_FOUND");
    }
    assertThat(get("/api/v1/signs/" + UUID.randomUUID()).statusCode()).isEqualTo(404);
    assertThat(get("/api/v1/signs/" + approved).statusCode()).isEqualTo(200);
    assertThat(get("/api/v1/signs/" + review).statusCode()).isEqualTo(200);
  }

  /** docs/06 §9: MySQL hiểu SRID 4326 theo vĩ độ trước; API phải trả đúng lat, lng. */
  @Test
  void axisOrderIsPreserved() throws Exception {
    String cat = data.category("AX");
    String type = data.type("AX", cat);
    String id = data.sign(type, cat, 21.0285, 105.8048, "APPROVED");

    JsonNode body = ok("/api/v1/signs?bbox=105.80,21.02,105.81,21.03&signTypeCodes=" + q(type));

    assertThat(body.get("items").size()).isEqualTo(1);
    JsonNode item = body.get("items").get(0);
    assertThat(item.get("id").asString()).isEqualTo(id);
    assertThat(item.get("location").get("lat").asDouble()).isEqualTo(21.0285);
    assertThat(item.get("location").get("lng").asDouble()).isEqualTo(105.8048);

    JsonNode detail = ok("/api/v1/signs/" + id);
    assertThat(detail.get("location").get("lat").asDouble()).isEqualTo(21.0285);
    assertThat(detail.get("location").get("lng").asDouble()).isEqualTo(105.8048);
  }

  /** Zoom thấp: vùng gần cả thế giới vẫn chứa biển ở Hà Nội (đa giác được chia cạnh ≤ 90°). */
  @Test
  void worldWideBboxStillMatches() throws Exception {
    String cat = data.category("WW");
    String type = data.type("WW", cat);
    String id = data.sign(type, cat, 21.0285, 105.8048, "APPROVED");

    JsonNode body = ok("/api/v1/signs?bbox=-180,-85,180,85&zoom=0&signTypeCodes=" + q(type));

    assertThat(body.get("mode").asString()).isEqualTo("SIGNS");
    assertThat(ids(body)).containsExactly(id);
  }

  @Test
  void detailHasPublicFieldsAndSignedCoverCrop() throws Exception {
    String cat = data.category("DT");
    String type = data.type("DT", cat);
    String withCover = data.sign(type, cat, -34.95, -119.95, "APPROVED", "covers/a.jpg");
    String withoutCover = data.sign(type, cat, -34.94, -119.94, "UNDER_REVIEW");

    JsonNode detail = ok("/api/v1/signs/" + withCover);
    assertThat(detail.get("id").asString()).isEqualTo(withCover);
    assertThat(detail.get("signTypeCode").asString()).isEqualTo(type);
    assertThat(detail.get("categoryCode").asString()).isEqualTo(cat);
    assertThat(detail.get("headingDeg").asInt()).isEqualTo(90);
    assertThat(detail.get("status").asString()).isEqualTo("APPROVED");
    assertThat(detail.get("confidence").asDouble()).isEqualTo(0.75);
    assertThat(detail.get("confirmCount").asInt()).isEqualTo(3);
    assertThat(detail.get("denyCount").asInt()).isEqualTo(1);
    assertThat(detail.get("updatedAt").asString()).endsWith("Z");
    assertThat(detail.get("coverCropUrl").asString())
        .contains("/sign-crops/covers/a.jpg")
        .contains("X-Amz-Signature")
        .contains("X-Amz-Expires=600");

    assertThat(ok("/api/v1/signs/" + withoutCover).get("coverCropUrl").isNull()).isTrue();
  }

  @Test
  void invalidBboxIsValidationError() throws Exception {
    HttpResponse<String> response = get("/api/v1/signs?bbox=179,10,-179,11");

    assertThat(response.statusCode()).isEqualTo(400);
    JsonNode body = json.readTree(response.body());
    assertThat(body.get("code").asString()).isEqualTo("VALIDATION_ERROR");
    assertThat(body.get("details").get(0).get("field").asString()).isEqualTo("bbox");
  }

  private JsonNode ok(String path) throws IOException, InterruptedException {
    HttpResponse<String> response = get(path);
    assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
    return json.readTree(response.body());
  }

  private HttpResponse<String> get(String path) throws IOException, InterruptedException {
    return http.send(
        HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET().build(),
        HttpResponse.BodyHandlers.ofString());
  }

  private static List<String> ids(JsonNode body) {
    List<String> ids = new ArrayList<>();
    body.get("items").forEach(item -> ids.add(item.get("id").asString()));
    return ids;
  }

  private static String q(String value) {
    return URLEncoder.encode(value, StandardCharsets.UTF_8);
  }
}
