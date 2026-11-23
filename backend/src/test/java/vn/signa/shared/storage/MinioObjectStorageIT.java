package vn.signa.shared.storage;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import vn.signa.shared.config.SignaProperties;
import vn.signa.support.IntegrationTest;

/** Đọc ghi MinIO thật và quyền truy cập từng bucket (docs/06 §11). */
class MinioObjectStorageIT extends IntegrationTest {

  private final HttpClient http = HttpClient.newHttpClient();

  @Autowired ObjectStorage storage;
  @Autowired SignaProperties properties;

  @Test
  void putThenPresignedGetReadsBack() throws Exception {
    String key = newKey();
    byte[] data = "ảnh cắt thử".getBytes(StandardCharsets.UTF_8);
    storage.put(Bucket.SIGN_CROPS, key, data, "image/jpeg");

    assertThat(storage.exists(Bucket.SIGN_CROPS, key)).isTrue();
    String url = storage.presignedGet(Bucket.SIGN_CROPS, key, Duration.ofMinutes(10));
    assertThat(url).startsWith(properties.minio().publicEndpoint());

    HttpResponse<byte[]> response = get(url);
    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(response.body()).isEqualTo(data);
    assertThat(response.headers().firstValue("Content-Type")).hasValue("image/jpeg");
  }

  @Test
  void privateBucketsRejectUnsignedRead() throws Exception {
    for (Bucket bucket : new Bucket[] {Bucket.SIGN_CROPS, Bucket.REPORT_PHOTOS}) {
      String key = newKey();
      storage.put(bucket, key, new byte[] {1, 2, 3}, "image/jpeg");

      assertThat(get(unsignedUrl(bucket, key)).statusCode()).as(bucket.name()).isEqualTo(403);
    }
  }

  @Test
  void signIconsArePubliclyReadable() throws Exception {
    String key = newKey();
    byte[] data = {4, 5, 6};
    storage.put(Bucket.SIGN_ICONS, key, data, "image/png");

    HttpResponse<byte[]> response = get(unsignedUrl(Bucket.SIGN_ICONS, key));
    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(response.body()).isEqualTo(data);
  }

  @Test
  void deleteRemovesObjectAndIsIdempotent() {
    String key = newKey();
    storage.put(Bucket.REPORT_PHOTOS, key, new byte[] {7}, "image/jpeg");

    storage.delete(Bucket.REPORT_PHOTOS, key);
    assertThat(storage.exists(Bucket.REPORT_PHOTOS, key)).isFalse();
    storage.delete(Bucket.REPORT_PHOTOS, key);
  }

  @Test
  void missingKeyDoesNotExist() {
    assertThat(storage.exists(Bucket.SIGN_CROPS, newKey())).isFalse();
  }

  private static String newKey() {
    return UUID.randomUUID() + ".jpg";
  }

  private String unsignedUrl(Bucket bucket, String key) {
    return properties.minio().endpoint() + "/" + bucket.bucketName() + "/" + key;
  }

  private HttpResponse<byte[]> get(String url) throws IOException, InterruptedException {
    return http.send(
        HttpRequest.newBuilder(URI.create(url)).GET().build(),
        HttpResponse.BodyHandlers.ofByteArray());
  }
}
