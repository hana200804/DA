package vn.signa.recognition.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import vn.signa.shared.error.ApiException;
import vn.signa.shared.error.ErrorCode;
import vn.signa.shared.image.ImageFormat;

class ImageFormatTest {

  @Test
  void detectsJpegPngWebpFromMagicBytes() throws Exception {
    assertThat(ImageFormat.detect(TestImages.encode(TestImages.solid(4, 4), "jpg")))
        .isEqualTo(ImageFormat.JPEG);
    assertThat(ImageFormat.detect(TestImages.encode(TestImages.solid(4, 4), "png")))
        .isEqualTo(ImageFormat.PNG);
    byte[] webp = ascii("RIFF\0\0\0\0WEBPVP8 ");
    assertThat(ImageFormat.detect(webp)).isEqualTo(ImageFormat.WEBP);
  }

  @Test
  void everyAcceptedFormatHasAnImageReader() {
    for (ImageFormat format : ImageFormat.values()) {
      assertThat(javax.imageio.ImageIO.getImageReadersByFormatName(format.extension()).hasNext())
          .as(format.name())
          .isTrue();
    }
  }

  @Test
  void rejectsTextEvenWhenNamedJpg() {
    assertUnsupported(ascii("hello, tôi là file .jpg"));
  }

  @Test
  void rejectsTruncatedOrRiffWithoutWebpTag() {
    assertUnsupported(new byte[] {(byte) 0xFF, (byte) 0xD8});
    assertUnsupported(new byte[0]);
    assertUnsupported(ascii("RIFF\0\0\0\0WAVEfmt "));
  }

  private static void assertUnsupported(byte[] data) {
    assertThatThrownBy(() -> ImageFormat.detect(data))
        .isInstanceOfSatisfying(
            ApiException.class,
            e -> assertThat(e.code()).isEqualTo(ErrorCode.UNSUPPORTED_MEDIA_TYPE));
  }

  private static byte[] ascii(String s) {
    return s.getBytes(StandardCharsets.UTF_8);
  }
}
