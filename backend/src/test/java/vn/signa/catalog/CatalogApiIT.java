package vn.signa.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import vn.signa.catalog.api.SignCatalog;
import vn.signa.catalog.internal.LabelImporter;
import vn.signa.support.IntegrationTest;

/** API danh mục công khai và {@link SignCatalog} trên container. Chỉ chạy trên CI. */
class CatalogApiIT extends IntegrationTest {

  private final HttpClient http = HttpClient.newHttpClient();

  @LocalServerPort int port;
  @Autowired LabelImporter importer;
  @Autowired JdbcClient jdbc;
  @Autowired StringRedisTemplate redis;
  @Autowired SignCatalog catalog;

  @BeforeEach
  void importLabels() {
    importer.importFrom(LabelImportIT.LABELS);
  }

  @AfterEach
  void reactivate() {
    jdbc.sql("UPDATE sign_types SET active = TRUE WHERE code = 'P.104'").update();
    redis.delete(cacheKey());
  }

  @Test
  void signTypesHonourIfNoneMatch() throws Exception {
    HttpResponse<String> first = get("/api/v1/sign-types", null);
    assertThat(first.statusCode()).isEqualTo(200);
    assertThat(first.body()).contains("\"labelMapVersion\":1", "\"code\":\"P.127_50\"");
    String etag = first.headers().firstValue("ETag").orElseThrow();
    assertThat(redis.hasKey(cacheKey())).isTrue();

    HttpResponse<String> second = get("/api/v1/sign-types", etag);
    assertThat(second.statusCode()).isEqualTo(304);
    assertThat(second.body()).isEmpty();

    assertThat(get("/api/v1/sign-types", "\"stale\"").statusCode()).isEqualTo(200);
  }

  @Test
  void unchangedImportKeepsCurrentCache() throws Exception {
    get("/api/v1/sign-types", null);
    assertThat(redis.hasKey(cacheKey())).isTrue();

    importer.importFrom(LabelImportIT.LABELS);

    assertThat(redis.hasKey(cacheKey())).isTrue();
  }

  @Test
  void inactiveTypeIsHidden() throws Exception {
    jdbc.sql("UPDATE sign_types SET active = FALSE WHERE code = 'P.104'").update();
    redis.delete(cacheKey());

    assertThat(get("/api/v1/sign-types", null).body())
        .doesNotContain("\"code\":\"P.104\"")
        .contains("\"code\":\"P.103a\"");
    assertThat(get("/api/v1/sign-types/P.104", null).statusCode()).isEqualTo(404);
    assertThat(get("/api/v1/sign-types/NOPE", null).statusCode()).isEqualTo(404);
    assertThat(get("/api/v1/sign-types/P.103a", null).statusCode()).isEqualTo(200);

    assertThat(catalog.findType("P.104")).hasValueSatisfying(t -> assertThat(t.active()).isFalse());
    assertThat(catalog.isActiveRecognizable("P.104", 1)).isFalse();
  }

  @Test
  void categoriesAndSignCatalog() throws Exception {
    HttpResponse<String> categories = get("/api/v1/sign-categories", null);
    assertThat(categories.statusCode()).isEqualTo(200);
    assertThat(categories.body()).contains("\"code\":\"PROHIBITORY\"", "\"sortOrder\":1");

    assertThat(catalog.categoryOf("W.245a")).contains("WARNING");
    assertThat(catalog.isActiveRecognizable("P.127_50", 1)).isTrue();
    assertThat(catalog.isActiveRecognizable("P.127_50", 2)).isFalse();
    assertThat(catalog.supportedLabelMapVersions()).contains(1);
  }

  private String cacheKey() {
    long generation =
        jdbc.sql("SELECT generation FROM catalog_generation WHERE id = 1")
            .query(Long.class)
            .single();
    return "cache:sign-types:" + generation;
  }

  private HttpResponse<String> get(String path, String ifNoneMatch) throws Exception {
    HttpRequest.Builder request =
        HttpRequest.newBuilder(URI.create("http://localhost:" + port + path));
    if (ifNoneMatch != null) request.header("If-None-Match", ifNoneMatch);
    return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
  }
}
