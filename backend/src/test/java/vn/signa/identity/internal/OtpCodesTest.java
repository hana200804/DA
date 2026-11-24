package vn.signa.identity.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.security.SecureRandom;
import org.junit.jupiter.api.Test;

class OtpCodesTest {

  @Test
  void padsSecureRandomNumberToSixDigits() {
    SecureRandom random = mock(SecureRandom.class);
    when(random.nextInt(1_000_000)).thenReturn(42, 999_999);
    OtpCodes codes = new OtpCodes("test-key", random);

    assertThat(codes.generate()).isEqualTo("000042");
    assertThat(codes.generate()).isEqualTo("999999");
  }

  @Test
  void computesHmacSha256AndComparesTheDigest() {
    OtpCodes codes = new OtpCodes("test-key", new SecureRandom());
    String hash = "dc2bcb9700275c7948309bba7f8b1d18ba9a14acdfb4a1754bf56a0f0ef65350";

    assertThat(codes.hash("000042")).isEqualTo(hash);
    assertThat(codes.matches("000042", hash)).isTrue();
    assertThat(codes.matches("000041", hash)).isFalse();
    assertThat(new OtpCodes("other-key", new SecureRandom()).matches("000042", hash)).isFalse();
    assertThat(OtpCodes.emailHash("a@b.vn"))
        .isEqualTo("c9c9a9e78c295f78fbd87946fa471af30ba446f46dd5cd0ec931a562b7c50ddd");
  }
}
