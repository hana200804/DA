package vn.signa.identity.internal;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;

class PasswordConfigTest {

  @Test
  void usesBcryptCost12() {
    PasswordEncoder encoder = new PasswordConfig().passwordEncoder();
    String hash = encoder.encode("mật-khẩu-thử");

    assertThat(hash).startsWith("$2a$12$").doesNotContain("mật-khẩu-thử");
    assertThat(encoder.matches("mật-khẩu-thử", hash)).isTrue();
  }
}
