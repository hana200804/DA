package vn.signa.shared.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import vn.signa.shared.config.SignaProperties;

/** Phần không cần MinIO thật. Phần đọc ghi nằm ở {@code MinioObjectStorageIT}. */
class MinioObjectStorageTest {

  /** Tên miền {@code .invalid} không phân giải được: nếu ký URL mà gọi mạng thì test lỗi. */
  private static MinioObjectStorage storage(String accessKey, String secretKey) {
    return new MinioObjectStorage(
        new SignaProperties(
            new SignaProperties.Minio(
                "http://minio.signa.invalid:9000",
                "https://files.signa.invalid",
                accessKey,
                secretKey,
                "us-east-1"),
            new SignaProperties.Recognition("http://recognition.signa.invalid"),
            "labels.yaml"));
  }

  @Test
  void presignedUrlUsesPublicEndpointWithoutNetwork() {
    String url =
        storage("test-access", "test-secret")
            .presignedGet(Bucket.SIGN_CROPS, "abc.jpg", Duration.ofMinutes(10));

    assertThat(url)
        .startsWith("https://files.signa.invalid/sign-crops/abc.jpg?")
        .contains("X-Amz-Expires=600")
        .contains("X-Amz-Credential=test-access%2F")
        .contains("X-Amz-Signature=");
  }

  @Test
  void presignedTtlMustBeWithinMinioLimits() {
    var storage = storage("test-access", "test-secret");

    assertThatThrownBy(() -> storage.presignedGet(Bucket.SIGN_CROPS, "a.jpg", Duration.ZERO))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() ->
            storage.presignedGet(Bucket.SIGN_CROPS, "a.jpg", Duration.ofDays(7).plusSeconds(1)))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void bucketInitRequiresCredentials() {
    assertThatThrownBy(() -> storage("", "").ensureBuckets())
        .isInstanceOf(ObjectStorageException.class)
        .hasMessageContaining("SIGNA_MINIO_ACCESS_KEY");
  }

  @Test
  void publicPolicyOnlyAllowsAnonymousGetOnThatBucket() {
    String policy = MinioObjectStorage.publicReadPolicy("sign-icons");

    assertThat(policy)
        .startsWith("{\"Version\":\"2012-10-17\"")
        .contains("\"Action\":[\"s3:GetObject\"]")
        .contains("\"Resource\":[\"arn:aws:s3:::sign-icons/*\"]")
        .doesNotContain("\n");
  }
}
