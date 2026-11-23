package vn.signa.identity.internal;

import java.nio.charset.StandardCharsets;

/**
 * Mật khẩu 8–72 ký tự (FR-01) và không quá 72 byte UTF-8, vì BCrypt chỉ dùng 72 byte đầu: hai mật
 * khẩu dài chung 72 byte đầu sẽ khớp nhau. Spring Security từ chối mật khẩu dài hơn.
 */
public final class PasswordPolicy {

  public static final int MIN_CHARS = 8;
  public static final int MAX_CHARS = 72;
  public static final int MAX_BYTES = 72;

  private PasswordPolicy() {}

  /** Lý do mật khẩu không hợp lệ, hoặc {@code null} nếu hợp lệ. */
  public static String problem(String raw) {
    if (raw == null) {
      return "bắt buộc";
    }
    int chars = raw.codePointCount(0, raw.length());
    if (chars < MIN_CHARS || chars > MAX_CHARS) {
      return "phải dài từ " + MIN_CHARS + " đến " + MAX_CHARS + " ký tự";
    }
    if (!fitsBcrypt(raw)) {
      return "không được dài quá " + MAX_BYTES + " byte UTF-8";
    }
    return null;
  }

  /** BCrypt của Spring Security ném lỗi với mật khẩu dài hơn 72 byte. */
  static boolean fitsBcrypt(String raw) {
    return raw.getBytes(StandardCharsets.UTF_8).length <= MAX_BYTES;
  }
}
