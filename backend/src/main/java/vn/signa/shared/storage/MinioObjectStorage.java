package vn.signa.shared.storage;

import io.minio.BucketExistsArgs;
import io.minio.GetPresignedObjectUrlArgs;
import io.minio.Http;
import io.minio.ListObjectsArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import io.minio.SetBucketPolicyArgs;
import io.minio.StatObjectArgs;
import io.minio.errors.ErrorResponseException;
import io.minio.errors.MinioException;
import java.time.Duration;
import java.util.Iterator;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import vn.signa.shared.config.SignaProperties;

/**
 * {@link ObjectStorage} trên MinIO (docs/06 §11). Dùng hai client: {@code client} gọi MinIO qua
 * mạng nội bộ, {@code signer} chỉ ký URL theo {@code signa.minio.public-endpoint} để điện thoại và
 * web mở được. Cả hai đặt sẵn region nên việc ký không gọi mạng.
 */
@Component
public class MinioObjectStorage implements ObjectStorage {

  /** MinIO giới hạn URL ký tối đa 7 ngày. */
  static final Duration MAX_TTL = Duration.ofDays(7);

  private final MinioClient client;
  private final MinioClient signer;
  private final boolean hasCredentials;

  public MinioObjectStorage(SignaProperties properties) {
    SignaProperties.Minio minio = properties.minio();
    this.hasCredentials =
        StringUtils.hasText(minio.accessKey()) && StringUtils.hasText(minio.secretKey());
    this.client = build(minio.endpoint(), minio);
    this.signer = build(minio.publicEndpoint(), minio);
  }

  private MinioClient build(String endpoint, SignaProperties.Minio minio) {
    MinioClient.Builder builder = MinioClient.builder().endpoint(endpoint).region(minio.region());
    if (hasCredentials) {
      builder.credentials(minio.accessKey(), minio.secretKey());
    }
    return builder.build();
  }

  /**
   * Tạo bucket còn thiếu; {@code sign-icons} được đặt quyền đọc công khai. Gọi khi ứng dụng khởi
   * động, chạy lại nhiều lần không sao.
   */
  public void ensureBuckets() {
    if (!hasCredentials) {
      throw new ObjectStorageException(
          "Thiếu SIGNA_MINIO_ACCESS_KEY hoặc SIGNA_MINIO_SECRET_KEY, không khởi tạo được bucket");
    }
    for (Bucket bucket : Bucket.values()) {
      String name = bucket.bucketName();
      try {
        if (!client.bucketExists(BucketExistsArgs.builder().bucket(name).build())) {
          client.makeBucket(MakeBucketArgs.builder().bucket(name).build());
        }
        if (bucket.publicRead()) {
          client.setBucketPolicy(
              SetBucketPolicyArgs.builder().bucket(name).config(publicReadPolicy(name)).build());
        }
      } catch (MinioException e) {
        throw new ObjectStorageException("Không khởi tạo được bucket " + name, e);
      }
    }
  }

  @Override
  public void put(Bucket bucket, String key, byte[] data, String contentType) {
    try {
      client.putObject(
          PutObjectArgs.builder()
              .bucket(bucket.bucketName())
              .object(key)
              .data(data, data.length)
              .contentType(contentType)
              .build());
    } catch (MinioException e) {
      throw failure("ghi", bucket, key, e);
    }
  }

  @Override
  public void delete(Bucket bucket, String key) {
    try {
      client.removeObject(
          RemoveObjectArgs.builder().bucket(bucket.bucketName()).object(key).build());
    } catch (MinioException e) {
      throw failure("xoá", bucket, key, e);
    }
  }

  @Override
  public boolean exists(Bucket bucket, String key) {
    try {
      client.statObject(StatObjectArgs.builder().bucket(bucket.bucketName()).object(key).build());
      return true;
    } catch (ErrorResponseException e) {
      if ("NoSuchKey".equals(e.errorResponse().code())) {
        return false;
      }
      throw failure("kiểm tra", bucket, key, e);
    } catch (MinioException e) {
      throw failure("kiểm tra", bucket, key, e);
    }
  }

  @Override
  public Iterable<StoredObject> list(Bucket bucket, String prefix) {
    var args =
        ListObjectsArgs.builder()
            .bucket(bucket.bucketName())
            .prefix(prefix)
            .recursive(true)
            .build();
    return () -> {
      var entries = client.listObjects(args).iterator();
      return new Iterator<>() {
        @Override
        public boolean hasNext() {
          return entries.hasNext();
        }

        @Override
        public StoredObject next() {
          try {
            var item = entries.next().get();
            return new StoredObject(item.objectName(), item.lastModified().toInstant());
          } catch (MinioException e) {
            throw failure("liệt kê", bucket, prefix, e);
          }
        }
      };
    };
  }

  @Override
  public String presignedGet(Bucket bucket, String key, Duration ttl) {
    if (ttl.compareTo(Duration.ofSeconds(1)) < 0 || ttl.compareTo(MAX_TTL) > 0) {
      throw new IllegalArgumentException("Thời hạn URL ký phải từ 1 giây tới 7 ngày: " + ttl);
    }
    try {
      return signer.getPresignedObjectUrl(
          GetPresignedObjectUrlArgs.builder()
              .method(Http.Method.GET)
              .bucket(bucket.bucketName())
              .object(key)
              .expiry((int) ttl.toSeconds())
              .build());
    } catch (MinioException e) {
      throw failure("ký URL", bucket, key, e);
    }
  }

  static String publicReadPolicy(String bucketName) {
    return """
    {"Version":"2012-10-17","Statement":[{"Effect":"Allow","Principal":{"AWS":["*"]},\
    "Action":["s3:GetObject"],"Resource":["arn:aws:s3:::%s/*"]}]}\
    """
        .formatted(bucketName);
  }

  private static ObjectStorageException failure(
      String action, Bucket bucket, String key, Exception cause) {
    return new ObjectStorageException(
        "Không " + action + " được " + bucket.bucketName() + "/" + key, cause);
  }
}
