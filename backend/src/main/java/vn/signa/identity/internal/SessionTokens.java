package vn.signa.identity.internal;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import java.util.regex.Pattern;

/**
 * Token phiên là 256 bit ngẫu nhiên mã hoá base64url (docs/03 §6.6). Redis chỉ giữ SHA-256 của
 * token, nên đọc được Redis cũng không dùng lại được phiên.
 */
final class SessionTokens {

  static final int BYTES = 32;

  /** 32 byte base64url không padding là đúng 43 ký tự. */
  private static final Pattern FORMAT = Pattern.compile("[A-Za-z0-9_-]{43}");

  private static final SecureRandom RANDOM = new SecureRandom();
  private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();

  private SessionTokens() {}

  static String newToken() {
    byte[] bytes = new byte[BYTES];
    RANDOM.nextBytes(bytes);
    return ENCODER.encodeToString(bytes);
  }

  /** Token sai định dạng bị loại trước khi tra Redis. */
  static boolean isWellFormed(String token) {
    return token != null && FORMAT.matcher(token).matches();
  }

  /** Mã phiên: SHA-256 hex của token, dùng làm khoá {@code session:{hash}}. */
  static String hash(String token) {
    try {
      MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
      return HexFormat.of().formatHex(sha256.digest(token.getBytes(StandardCharsets.US_ASCII)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("JVM thiếu SHA-256", e);
    }
  }
}
