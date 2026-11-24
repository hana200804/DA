package vn.signa.identity.internal;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

/** BCrypt cost 12 (docs/06 §4, NFR-10). */
@Configuration
class PasswordConfig {

  static final int BCRYPT_COST = 12;

  @Bean
  PasswordEncoder passwordEncoder() {
    return new BCryptPasswordEncoder(BCRYPT_COST);
  }
}
