package vn.signa.shared.image;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import vn.signa.recognition.internal.TestImages;
import vn.signa.shared.error.ApiException;
import vn.signa.shared.error.ErrorCode;

class ImageValidationTest {

  @Test
  void decodesSupportedImages() throws Exception {
    assertThat(ImageValidation.validate(TestImages.jpeg(4, 4), 8, 64)).isEqualTo(ImageFormat.JPEG);
    assertThat(ImageValidation.validate(TestImages.png(4, 4), 8, 64)).isEqualTo(ImageFormat.PNG);
    byte[] webp =
        Base64.getDecoder()
            .decode(
                "UklGRjwAAABXRUJQVlA4IDAAAADQAQCdASoEAAQAAUAmJaACdLoB+AADsAD+8U2v/MEr8g3ah/+e0fntH57R/54QAAA=");
    assertThat(ImageValidation.validate(webp, 8, 64)).isEqualTo(ImageFormat.WEBP);
  }

  @ParameterizedTest
  @ValueSource(strings = {"", "ffd8ff", "89504e470d0a1a0a", "524946460400000057454250"})
  void rejectsFilesThatOnlyContainAHeader(String hex) {
    assertThatThrownBy(() -> ImageValidation.validate(HexFormat.of().parseHex(hex), 8, 64))
        .isInstanceOfSatisfying(
            ApiException.class,
            e -> assertThat(e.code()).isEqualTo(ErrorCode.UNSUPPORTED_MEDIA_TYPE));
  }

  @Test
  void checksBothLimitsBeforeDecodingCompressedData() throws Exception {
    byte[] img = TestImages.pngDeclaring(16, 8);
    assertThatThrownBy(() -> ImageValidation.validate(img, 15, 128))
        .isInstanceOfSatisfying(
            ApiException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.PAYLOAD_TOO_LARGE));
    assertThatThrownBy(() -> ImageValidation.validate(img, 16, 127))
        .isInstanceOfSatisfying(
            ApiException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.PAYLOAD_TOO_LARGE));
  }

  @Test
  void rejectsAnIncompletePngAfterTheImageData() throws Exception {
    byte[] img = TestImages.png(4, 4);
    byte[] truncated = Arrays.copyOf(img, img.length - 12);

    assertThatThrownBy(() -> ImageValidation.validate(truncated, 8, 64))
        .isInstanceOfSatisfying(
            ApiException.class,
            e -> assertThat(e.code()).isEqualTo(ErrorCode.UNSUPPORTED_MEDIA_TYPE));
  }
}
