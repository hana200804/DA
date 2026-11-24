package vn.signa.catalog.internal;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ETagsTest {

  @Test
  void isQuotedHashOfContent() {
    String etag = ETags.of("{\"items\":[]}");

    assertThat(etag).matches("^\"[0-9a-f]{32}\"$");
    assertThat(ETags.of("{\"items\":[]}")).isEqualTo(etag);
  }

  @Test
  void changesWhenContentChanges() {
    assertThat(ETags.of("{\"name\":\"Cấm xe máy\"}"))
        .isNotEqualTo(ETags.of("{\"name\":\"Cấm xe may\"}"));
  }

  @Test
  void hashesUtf8Bytes() {
    // SHA-256("") bắt đầu bằng e3b0c44298fc1c149afbf4c8996fb924.
    assertThat(ETags.of("")).isEqualTo("\"e3b0c44298fc1c149afbf4c8996fb924\"");
  }
}
