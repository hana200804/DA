package vn.signa.identity.internal;

import java.util.Locale;

/**
 * Mẫu {@code LIKE} "chứa chuỗi" an toàn. Ký tự đại diện {@code %}, {@code _} và chính ký tự escape
 * được escape, nên người dùng không tạo được mẫu đại diện. Dùng ký tự escape {@code !} khai báo rõ
 * ({@code ESCAPE '!'}) thay vì {@code \}, để không phụ thuộc {@code sql_mode} NO_BACKSLASH_ESCAPES.
 */
final class LikePattern {

  static final char ESCAPE = '!';

  private LikePattern() {}

  /** {@code null} hoặc rỗng sau khi trim thì {@code null} (không lọc); còn lại lowercase. */
  static String contains(String text) {
    if (text == null || text.isBlank()) {
      return null;
    }
    String lower = text.trim().toLowerCase(Locale.ROOT);
    StringBuilder pattern = new StringBuilder(lower.length() + 2).append('%');
    for (char c : lower.toCharArray()) {
      if (c == '%' || c == '_' || c == ESCAPE) {
        pattern.append(ESCAPE);
      }
      pattern.append(c);
    }
    return pattern.append('%').toString();
  }
}
