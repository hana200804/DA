package vn.signa.shared.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class RequestIdFilterTest {

  @Test
  void keepsValidHeader() {
    assertThat(RequestIdFilter.sanitize("7f3c2a1e-abc.DEF_9")).isEqualTo("7f3c2a1e-abc.DEF_9");
  }

  @Test
  void generatesUuidWhenMissing() {
    assertThat(UUID.fromString(RequestIdFilter.sanitize(null))).isNotNull();
  }

  @Test
  void replacesInvalidHeader() {
    String tooLong = "a".repeat(65);
    assertThat(RequestIdFilter.sanitize(tooLong)).isNotEqualTo(tooLong).hasSize(36);
    assertThat(RequestIdFilter.sanitize("x\r\ninjected")).hasSize(36);
    assertThat(RequestIdFilter.sanitize("")).hasSize(36);
  }
}
