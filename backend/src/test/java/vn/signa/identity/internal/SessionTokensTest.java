package vn.signa.identity.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Base64;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

class SessionTokensTest {

  @Test
  void tokenIs32RandomBytesInBase64Url() {
    String token = SessionTokens.newToken();

    assertThat(token).hasSize(43).matches("[A-Za-z0-9_-]+");
    assertThat(Base64.getUrlDecoder().decode(token)).hasSize(32);
    assertThat(SessionTokens.isWellFormed(token)).isTrue();
  }

  @Test
  void tokensDoNotRepeat() {
    Set<String> seen = new HashSet<>();
    for (int i = 0; i < 1000; i++) {
      assertThat(seen.add(SessionTokens.newToken())).isTrue();
    }
  }

  @Test
  void hashIsSha256Hex() {
    assertThat(SessionTokens.hash("abc"))
        .isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
    String token = SessionTokens.newToken();
    assertThat(SessionTokens.hash(token)).hasSize(64).isEqualTo(SessionTokens.hash(token));
    assertThat(SessionTokens.hash(token)).doesNotContain(token);
  }

  @Test
  void rejectsMalformedTokens() {
    assertThat(SessionTokens.isWellFormed(null)).isFalse();
    assertThat(SessionTokens.isWellFormed("")).isFalse();
    assertThat(SessionTokens.isWellFormed("a".repeat(42))).isFalse();
    assertThat(SessionTokens.isWellFormed("a".repeat(44))).isFalse();
    assertThat(SessionTokens.isWellFormed("a".repeat(42) + "=")).isFalse();
    assertThat(SessionTokens.isWellFormed("a".repeat(42) + "+")).isFalse();
  }
}
