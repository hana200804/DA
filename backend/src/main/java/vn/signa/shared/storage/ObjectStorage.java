package vn.signa.shared.storage;

import java.time.Duration;

/** Lưu ảnh trong MinIO. Ảnh gửi chỉ để nhận diện không bao giờ đi qua interface này (NFR-09). */
public interface ObjectStorage {

  void put(Bucket bucket, String key, byte[] data, String contentType);

  void delete(Bucket bucket, String key);

  boolean exists(Bucket bucket, String key);

  /** URL đọc có chữ ký, dùng cho bucket riêng tư. */
  String presignedGet(Bucket bucket, String key, Duration ttl);
}
