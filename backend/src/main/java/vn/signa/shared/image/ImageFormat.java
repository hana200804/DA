package vn.signa.shared.image;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import org.springframework.http.MediaType;
import vn.signa.shared.error.ApiException;
import vn.signa.shared.error.ErrorCode;

/** Định dạng ảnh được chấp nhận, xác định bằng magic bytes thay vì đuôi file. */
public enum ImageFormat {
  JPEG(MediaType.IMAGE_JPEG, "jpg"),
  PNG(MediaType.IMAGE_PNG, "png"),
  WEBP(MediaType.parseMediaType("image/webp"), "webp");

  private static final byte[] JPEG_MAGIC = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF};
  private static final byte[] PNG_MAGIC = {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A};
  private static final byte[] RIFF = "RIFF".getBytes(StandardCharsets.US_ASCII);
  private static final byte[] WEBP_TAG = "WEBP".getBytes(StandardCharsets.US_ASCII);

  private final MediaType mediaType;
  private final String extension;

  ImageFormat(MediaType mediaType, String extension) {
    this.mediaType = mediaType;
    this.extension = extension;
  }

  public MediaType mediaType() {
    return mediaType;
  }

  public String extension() {
    return extension;
  }

  /**
   * Nhận dạng JPEG, PNG, WebP từ các byte đầu.
   *
   * @throws ApiException {@code UNSUPPORTED_MEDIA_TYPE} nếu không phải ba định dạng trên
   */
  public static ImageFormat detect(byte[] data) {
    if (startsWith(data, 0, JPEG_MAGIC)) {
      return JPEG;
    }
    if (startsWith(data, 0, PNG_MAGIC)) {
      return PNG;
    }
    if (startsWith(data, 0, RIFF) && startsWith(data, 8, WEBP_TAG)) {
      return WEBP;
    }
    throw unsupported();
  }

  public static ApiException unsupported() {
    return new ApiException(
        ErrorCode.UNSUPPORTED_MEDIA_TYPE, "Ảnh phải là JPEG, PNG hoặc WebP hợp lệ");
  }

  private static boolean startsWith(byte[] data, int offset, byte[] prefix) {
    return data.length >= offset + prefix.length
        && Arrays.equals(data, offset, offset + prefix.length, prefix, 0, prefix.length);
  }
}
