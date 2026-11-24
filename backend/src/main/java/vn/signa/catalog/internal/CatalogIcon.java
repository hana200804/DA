package vn.signa.catalog.internal;

import java.util.HexFormat;
import vn.signa.shared.error.ApiException;
import vn.signa.shared.error.ErrorCode;

/** Định dạng ảnh minh hoạ, lấy từ chữ ký của tệp. */
enum CatalogIcon {
  PNG("png", "image/png", "89504e470d0a1a0a"),
  JPEG("jpg", "image/jpeg", "ffd8ff"),
  WEBP("webp", "image/webp", "52494646");

  private static final byte[] WEBP_MARKER = HexFormat.of().parseHex("57454250");

  final String extension;
  final String contentType;
  private final byte[] signature;

  CatalogIcon(String extension, String contentType, String signature) {
    this.extension = extension;
    this.contentType = contentType;
    this.signature = HexFormat.of().parseHex(signature);
  }

  static CatalogIcon detect(byte[] bytes) {
    for (CatalogIcon format : values()) {
      if (matches(bytes, 0, format.signature)
          && (format != WEBP || matches(bytes, 8, WEBP_MARKER))) {
        return format;
      }
    }
    throw new ApiException(
        ErrorCode.UNSUPPORTED_MEDIA_TYPE, "Ảnh minh hoạ phải là PNG, JPEG hoặc WebP");
  }

  private static boolean matches(byte[] bytes, int offset, byte[] signature) {
    if (bytes.length < offset + signature.length) return false;
    for (int i = 0; i < signature.length; i++) {
      if (bytes[offset + i] != signature[i]) return false;
    }
    return true;
  }
}
