package vn.signa.shared.storage;

/** Lỗi khi đọc ghi MinIO. Bọc lỗi của SDK để module nghiệp vụ không phụ thuộc MinIO. */
public class ObjectStorageException extends RuntimeException {

  public ObjectStorageException(String message, Throwable cause) {
    super(message, cause);
  }

  public ObjectStorageException(String message) {
    super(message);
  }
}
