package vn.signa.identity.internal;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class PasswordPolicyTest {

  @Test
  void acceptsEightToSeventyTwoAsciiCharacters() {
    assertThat(PasswordPolicy.problem("12345678")).isNull();
    assertThat(PasswordPolicy.problem("a".repeat(72))).isNull();
  }

  @Test
  void rejectsTooShortTooLongOrMissing() {
    assertThat(PasswordPolicy.problem(null)).isNotNull();
    assertThat(PasswordPolicy.problem("1234567")).contains("8");
    assertThat(PasswordPolicy.problem("a".repeat(73))).contains("72 ký tự");
  }

  @Test
  void rejectsMoreThan72Utf8Bytes() {
    // 25 ký tự "ệ" là 75 byte UTF-8: đủ ký tự nhưng BCrypt sẽ cắt.
    String vietnamese = "ệ".repeat(25);
    assertThat(PasswordPolicy.problem(vietnamese)).contains("byte");
    assertThat(PasswordPolicy.problem("ệ".repeat(24))).isNull();
  }

  @Test
  void countsCodePointsNotUtf16Units() {
    // 7 emoji là 14 đơn vị UTF-16 nhưng chỉ 7 ký tự.
    assertThat(PasswordPolicy.problem("😀".repeat(7))).contains("8");
  }
}
