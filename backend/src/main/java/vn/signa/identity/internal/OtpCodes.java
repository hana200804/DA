package vn.signa.identity.internal;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.Locale;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

final class OtpCodes {

  private static final String ALGORITHM = "HmacSHA256";
  private final SecretKeySpec secret;
  private final SecureRandom random;

  OtpCodes(String secret, SecureRandom random) {
    this.secret = new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), ALGORITHM);
    this.random = random;
  }

  String generate() {
    return String.format(Locale.ROOT, "%06d", random.nextInt(1_000_000));
  }

  String hash(String code) {
    return HexFormat.of().formatHex(digest(code));
  }

  boolean matches(String code, String codeHash) {
    return MessageDigest.isEqual(HexFormat.of().parseHex(codeHash), digest(code));
  }

  /** Email đã chuẩn hoá tại tầng web. */
  static String emailHash(String email) {
    try {
      byte[] hash =
          MessageDigest.getInstance("SHA-256").digest(email.getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(hash);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("JVM thiếu SHA-256", e);
    }
  }

  private byte[] digest(String code) {
    try {
      Mac mac = Mac.getInstance(ALGORITHM);
      mac.init(secret);
      return mac.doFinal(code.getBytes(StandardCharsets.UTF_8));
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException("Không thể băm OTP bằng HMAC-SHA256", e);
    }
  }
}
