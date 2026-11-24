package vn.signa.sign.map;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import vn.signa.support.IntegrationTest;

/**
 * Đo nhanh NFR-03 (p95 &lt; 300 ms với ≤ 500 biển). Chỉ in kết quả ra log, không assert thời gian
 * vì máy CI dao động; nghiệm thu chính thức bằng k6 ở lát 10.
 */
class MapPerformanceIT extends IntegrationTest {

  private static final Logger log = LoggerFactory.getLogger(MapPerformanceIT.class);
  private static final int WARMUP = 5;
  private static final int CALLS = 50;

  private final HttpClient http = HttpClient.newHttpClient();

  @LocalServerPort int port;
  @Autowired JdbcTemplate jdbc;
  @Autowired ObjectMapper json;

  @Test
  void measureP95For500Signs() throws Exception {
    MapFixtures data = new MapFixtures(jdbc);
    String cat = data.category("PF");
    String type = data.type("PF", cat);
    data.grid(type, cat, 0.01, -119.99, 25, 20, 0.04);
    HttpRequest request =
        HttpRequest.newBuilder(
                URI.create("http://localhost:" + port + "/api/v1/signs?bbox=-120,0,-119,1&zoom=12"))
            .GET()
            .build();

    for (int i = 0; i < WARMUP; i++) {
      http.send(request, HttpResponse.BodyHandlers.ofString());
    }
    long[] millis = new long[CALLS];
    HttpResponse<String> last = null;
    for (int i = 0; i < CALLS; i++) {
      long start = System.nanoTime();
      last = http.send(request, HttpResponse.BodyHandlers.ofString());
      millis[i] = (System.nanoTime() - start) / 1_000_000;
    }

    assertThat(last.statusCode()).isEqualTo(200);
    JsonNode body = json.readTree(last.body());
    assertThat(body.get("mode").asString()).isEqualTo("SIGNS");
    assertThat(body.get("items").size()).isEqualTo(500);

    Arrays.sort(millis);
    long p50 = millis[(int) Math.ceil(0.50 * CALLS) - 1];
    long p95 = millis[(int) Math.ceil(0.95 * CALLS) - 1];
    String summary =
        "NFR-03 GET /signs 500 biển, %d lần: p50=%d ms, p95=%d ms, max=%d ms"
            .formatted(CALLS, p50, p95, millis[CALLS - 1]);
    log.info(summary);
    System.out.println(summary);
  }
}
