package vn.signa.identity.web;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class SessionAuthenticationFilterTest {

  @Test
  void readsBearerToken() {
    assertThat(SessionAuthenticationFilter.bearer("Bearer abc")).isEqualTo("abc");
    assertThat(SessionAuthenticationFilter.bearer("bearer  abc ")).isEqualTo("abc");
  }

  @Test
  void ignoresOtherSchemesAndEmptyToken() {
    assertThat(SessionAuthenticationFilter.bearer(null)).isNull();
    assertThat(SessionAuthenticationFilter.bearer("Basic dTpw")).isNull();
    assertThat(SessionAuthenticationFilter.bearer("Bearer ")).isNull();
    assertThat(SessionAuthenticationFilter.bearer("Bearerabc")).isNull();
  }
}
