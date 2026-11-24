package vn.signa.sign.internal.feedback;

import java.util.Arrays;
import java.util.HexFormat;
import vn.signa.shared.error.ApiException;
import vn.signa.shared.error.ErrorCode;

enum ReportPhoto {
  JPEG("jpg", "image/jpeg"),
  PNG("png", "image/png"),
  WEBP("webp", "image/webp");

  static final int MAX_BYTES = 5 * 1024 * 1024;
  private static final byte[] JPEG_MAGIC = HexFormat.of().parseHex("ffd8ff");
  private static final byte[] PNG_MAGIC = HexFormat.of().parseHex("89504e470d0a1a0a");
  private static final byte[] RIFF = HexFormat.of().parseHex("52494646");
  private static final byte[] WEBP_TAG = HexFormat.of().parseHex("57454250");

  final String extension;
  final String contentType;

  ReportPhoto(String extension, String contentType) {
    this.extension = extension;
    this.contentType = contentType;
  }

  static ReportPhoto detect(byte[] data) {
    if (data.length > MAX_BYTES) throw new ApiException(ErrorCode.PAYLOAD_TOO_LARGE);
    if (startsWith(data, 0, JPEG_MAGIC)) return JPEG;
    if (startsWith(data, 0, PNG_MAGIC)) return PNG;
    if (startsWith(data, 0, RIFF) && startsWith(data, 8, WEBP_TAG)) return WEBP;
    throw new ApiException(ErrorCode.UNSUPPORTED_MEDIA_TYPE, "Ảnh phải là JPEG, PNG hoặc WebP");
  }

  private static boolean startsWith(byte[] data, int offset, byte[] prefix) {
    return data.length >= offset + prefix.length
        && Arrays.equals(data, offset, offset + prefix.length, prefix, 0, prefix.length);
  }
}
