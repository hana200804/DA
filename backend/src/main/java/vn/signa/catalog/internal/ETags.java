package vn.signa.catalog.internal;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** ETag mạnh từ nội dung: 128 bit đầu của SHA-256, trong dấu nháy kép (RFC 9110 §8.8.3). */
public final class ETags {

  private ETags() {}

  public static String of(String body) {
    try {
      byte[] hash =
          MessageDigest.getInstance("SHA-256").digest(body.getBytes(StandardCharsets.UTF_8));
      return "\"" + HexFormat.of().formatHex(hash, 0, 16) + "\"";
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("JVM thiếu SHA-256", e);
    }
  }
}
