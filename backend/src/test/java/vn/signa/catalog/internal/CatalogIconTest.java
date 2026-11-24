package vn.signa.catalog.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import java.util.HexFormat;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import vn.signa.shared.error.ApiException;
import vn.signa.shared.error.ErrorCode;

class CatalogIconTest {

  @ParameterizedTest
  @CsvSource({
    "89504e470d0a1a0a, png, image/png",
    "ffd8ffe0, jpg, image/jpeg",
    "524946460000000057454250, webp, image/webp"
  })
  void detectsSupportedImageSignatures(String hex, String extension, String contentType) {
    CatalogIcon format = CatalogIcon.detect(HexFormat.of().parseHex(hex));
    assertThat(format.extension).isEqualTo(extension);
    assertThat(format.contentType).isEqualTo(contentType);
  }

  @ParameterizedTest
  @ValueSource(strings = {"", "89504e47", "474946383961", "524946460000000057415645"})
  void rejectsUnsupportedOrTruncatedSignatures(String hex) {
    assertThatExceptionOfType(ApiException.class)
        .isThrownBy(() -> CatalogIcon.detect(HexFormat.of().parseHex(hex)))
        .satisfies(e -> assertThat(e.code()).isEqualTo(ErrorCode.UNSUPPORTED_MEDIA_TYPE));
  }
}
