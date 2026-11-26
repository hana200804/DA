package vn.signa.shared.storage;

import java.time.Duration;
import java.time.Instant;

/** Lưu ảnh trong MinIO. Ảnh gửi chỉ để nhận diện không bao giờ đi qua interface này (NFR-09). */
public interface ObjectStorage {

  void put(Bucket bucket, String key, byte[] data, String contentType);

  void delete(Bucket bucket, String key);

  boolean exists(Bucket bucket, String key);

  /** Liệt kê khoá và thời điểm sửa cuối theo prefix, đọc từng trang từ kho đối tượng. */
  Iterable<StoredObject> list(Bucket bucket, String prefix);

  record StoredObject(String key, Instant lastModified) {}

  /** URL đọc có chữ ký, dùng cho bucket riêng tư. */
  String presignedGet(Bucket bucket, String key, Duration ttl);
}
