package vn.signa;

import static org.assertj.core.api.Assertions.assertThat;

import io.minio.BucketExistsArgs;
import io.minio.MinioClient;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.web.server.LocalServerPort;
import vn.signa.shared.storage.Bucket;
import vn.signa.shared.web.RequestIdFilter;
import vn.signa.support.Containers;
import vn.signa.support.IntegrationTest;

/** Ứng dụng khởi động trên container (nghiệm thu lát 0, docs/backend/thiet-ke-trien-khai.md §6). */
class ApplicationIT extends IntegrationTest {

  private final HttpClient http = HttpClient.newHttpClient();

  @LocalServerPort int port;

  @Test
  void healthIsUp() throws Exception {
    HttpResponse<String> response = get("/actuator/health");

    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(response.body()).contains("\"status\":\"UP\"");
    assertThat(response.headers().firstValue(RequestIdFilter.HEADER)).isPresent();
  }

  @Test
  void openApiIsServed() throws Exception {
    HttpResponse<String> response = get("/api/v1/openapi.json");

    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(response.body()).contains("\"openapi\"");
  }

  @Test
  void bucketsExistAfterStartup() throws Exception {
    MinioClient minio =
        MinioClient.builder()
            .endpoint(Containers.MINIO.getS3URL())
            .credentials(Containers.MINIO.getUserName(), Containers.MINIO.getPassword())
            .build();

    for (Bucket bucket : Bucket.values()) {
      assertThat(minio.bucketExists(BucketExistsArgs.builder().bucket(bucket.bucketName()).build()))
          .as(bucket.bucketName())
          .isTrue();
    }
  }

  private HttpResponse<String> get(String path) throws IOException, InterruptedException {
    return http.send(
        HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET().build(),
        HttpResponse.BodyHandlers.ofString());
  }
}
