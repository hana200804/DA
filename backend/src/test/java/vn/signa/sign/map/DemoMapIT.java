package vn.signa.sign.map;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import vn.signa.support.Containers;
import vn.signa.support.IntegrationTest;

/**
 * Bản đồ trên dữ liệu mẫu của profile {@code demo} (lát 2, {@code db/demo/R__demo_data.sql}): 30
 * biển quanh Hà Nội, 18 {@code APPROVED}, 6 {@code UNDER_REVIEW}, 6 {@code PENDING}.
 *
 * <p>Nạp file bằng {@link ResourceDatabasePopulator} với tài khoản migrator, giống {@code
 * DemoDataIT}, thay vì bật profile {@code demo} cho context: Flyway sẽ ghi dòng {@code R__} vào
 * lịch sử migration dùng chung và các context IT khác (không có {@code classpath:db/demo}) báo
 * migration thiếu. Chỉ xét biển có UUID mẫu vì IT khác cũng chèn biển ở Hà Nội. Chỉ chạy trên CI.
 */
class DemoMapIT extends IntegrationTest {

  private static final String DEMO_IDS = "id LIKE '00000000-0000-4000-8000-%'";
  private static final String HANOI = "/api/v1/signs?bbox=105.79,21.01,105.86,21.05";

  private final HttpClient http = HttpClient.newHttpClient();
  private final DriverManagerDataSource migrator =
      new DriverManagerDataSource(
          Containers.MYSQL.getJdbcUrl(), Containers.MIGRATOR_USER, Containers.MIGRATOR_PASSWORD);

  @LocalServerPort int port;
  @Autowired JdbcClient jdbc;
  @Autowired ObjectMapper json;

  @BeforeEach
  void loadDemoData() {
    new ResourceDatabasePopulator(new ClassPathResource("db/demo/R__demo_data.sql"))
        .execute(migrator);
  }

  @AfterEach
  void removeDemoSigns() {
    JdbcClient.create(migrator).sql("DELETE FROM signs WHERE " + DEMO_IDS).update();
  }

  @Test
  void hanoiShowsPublishedDemoSignsOnly() throws Exception {
    List<String> published = demoIds("status IN ('APPROVED', 'UNDER_REVIEW')");
    List<String> pending = demoIds("status = 'PENDING'");
    assertThat(published).hasSize(24);
    assertThat(pending).hasSize(6);

    JsonNode body = ok(HANOI);

    assertThat(body.get("mode").asString()).isEqualTo("SIGNS");
    assertThat(demoItems(body)).containsExactlyInAnyOrderElementsOf(published);
    body.get("items")
        .forEach(
            item -> assertThat(item.get("status").asString()).isIn("APPROVED", "UNDER_REVIEW"));
  }

  @Test
  void categoryFilterOnDemoData() throws Exception {
    List<String> prohibitory =
        demoIds("status IN ('APPROVED', 'UNDER_REVIEW') AND category_code = 'PROHIBITORY'");
    assertThat(prohibitory).isNotEmpty();

    assertThat(demoItems(ok(HANOI + "&categories=PROHIBITORY")))
        .containsExactlyInAnyOrderElementsOf(prohibitory);
  }

  @Test
  void pendingDemoSignDetailIs404() throws Exception {
    String pending = demoIds("status = 'PENDING'").getFirst();
    String approved = demoIds("status = 'APPROVED'").getFirst();

    assertThat(get("/api/v1/signs/" + pending).statusCode()).isEqualTo(404);
    assertThat(ok("/api/v1/signs/" + approved).get("id").asString()).isEqualTo(approved);
  }

  private List<String> demoIds(String condition) {
    return jdbc.sql("SELECT id FROM signs WHERE " + DEMO_IDS + " AND " + condition)
        .query(String.class)
        .list();
  }

  private static List<String> demoItems(JsonNode body) {
    List<String> ids = new ArrayList<>();
    body.get("items")
        .forEach(item -> {
          String id = item.get("id").asString();
          if (id.startsWith("00000000-0000-4000-8000-")) ids.add(id);
        });
    return ids;
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
}
