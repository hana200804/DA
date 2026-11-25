package vn.signa.recognition.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import java.awt.image.BufferedImage;
import java.util.Arrays;
import java.util.concurrent.Semaphore;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import vn.signa.shared.error.ApiException;
import vn.signa.shared.error.ErrorCode;
import vn.signa.shared.image.ImageFormat;

class ImageProcessorTest {

  private final ImageProcessor processor = new ImageProcessor();

  @Test
  void largeImageIsShrunkToLongEdge1280AsJpeg() throws Exception {
    PreparedImage prepared = processor.prepare(TestImages.png(2560, 1920));

    assertThat(prepared.width()).isEqualTo(2560);
    assertThat(prepared.height()).isEqualTo(1920);
    assertThat(prepared.sentWidth()).isEqualTo(1280);
    assertThat(prepared.sentHeight()).isEqualTo(960);
    assertThat(prepared.mediaType()).isEqualTo(MediaType.IMAGE_JPEG);
    assertThat(ImageFormat.detect(prepared.payload())).isEqualTo(ImageFormat.JPEG);
    BufferedImage sent = TestImages.decode(prepared.payload());
    assertThat(sent.getWidth()).isEqualTo(1280);
    assertThat(sent.getHeight()).isEqualTo(960);
  }

  @Test
  void portraitImageShrinksByHeight() throws Exception {
    PreparedImage prepared = processor.prepare(TestImages.jpeg(1000, 3000));

    assertThat(prepared.sentWidth()).isEqualTo(427);
    assertThat(prepared.sentHeight()).isEqualTo(1280);
  }

  @Test
  void wideAndTallImagesArePaddedToTheAiMinimumEdge() throws Exception {
    PreparedImage wide = processor.prepare(TestImages.png(4096, 20));
    PreparedImage tall = processor.prepare(TestImages.png(20, 4096));

    assertThat(TestImages.decode(wide.payload()).getHeight()).isEqualTo(8);
    assertThat(TestImages.decode(tall.payload()).getWidth()).isEqualTo(8);
    assertThat(wide.sentWidth()).isEqualTo(1280);
    assertThat(tall.sentHeight()).isEqualTo(1280);
    assertThat(wide.toOriginal(new Box(5, 3, 10, 5))).isEqualTo(new Box(16, 10, 32, 10));
    assertThat(tall.toOriginal(new Box(3, 5, 5, 10))).isEqualTo(new Box(10, 16, 10, 32));
  }

  @Test
  void smallImageIsSentUnchanged() throws Exception {
    byte[] png = TestImages.png(1280, 720);

    PreparedImage prepared = processor.prepare(png);

    assertThat(prepared.payload()).isSameAs(png);
    assertThat(prepared.mediaType()).isEqualTo(MediaType.IMAGE_PNG);
    assertThat(prepared.sentWidth()).isEqualTo(1280);
    assertThat(prepared.sentHeight()).isEqualTo(720);
  }

  @Test
  void boxIsScaledBackToOriginalCoordinates() throws Exception {
    PreparedImage prepared = processor.prepare(TestImages.png(2560, 1920));

    Box original = prepared.toOriginal(new Box(100, 50, 64, 66));

    assertThat(original.x()).isCloseTo(200, within(1e-9));
    assertThat(original.y()).isCloseTo(100, within(1e-9));
    assertThat(original.width()).isCloseTo(128, within(1e-9));
    assertThat(original.height()).isCloseTo(132, within(1e-9));
  }

  @Test
  void boxScaleUsesEachAxisSeparately() {
    Box scaled = new Box(10, 20, 30, 40).scale(2.0, 0.5);

    assertThat(scaled).isEqualTo(new Box(20, 10, 60, 20));
  }

  @Test
  void decompressionBombIsRejectedBeforeDecoding() throws Exception {
    byte[] bomb = TestImages.pngDeclaring(6000, 6000);

    assertThatThrownBy(() -> processor.prepare(bomb))
        .isInstanceOfSatisfying(
            ApiException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.PAYLOAD_TOO_LARGE));
  }

  @Test
  void returnsRetryAfterWhileOtherDecodesUseThePixelBudget() throws Exception {
    var budget = new Semaphore(2_000_000);
    var limited = new ImageProcessor(budget);
    byte[] large = TestImages.png(1600, 1000);
    budget.acquire(500_000);

    assertThatThrownBy(() -> limited.prepare(large))
        .isInstanceOfSatisfying(
            ApiException.class,
            e -> {
              assertThat(e.code()).isEqualTo(ErrorCode.RECOGNITION_UNAVAILABLE);
              assertThat(e.retryAfterSeconds()).isEqualTo(1);
            });
    budget.release(500_000);

    assertThat(limited.prepare(large).sentWidth()).isEqualTo(1280);
    assertThat(budget.availablePermits()).isEqualTo(2_000_000);
  }

  @Test
  void corruptDataWithValidMagicIsUnsupported() throws Exception {
    byte[] png = TestImages.png(2000, 2000);
    byte[] truncated = Arrays.copyOf(png, 40);

    assertThatThrownBy(() -> processor.prepare(truncated))
        .isInstanceOfSatisfying(
            ApiException.class,
            e -> assertThat(e.code()).isEqualTo(ErrorCode.UNSUPPORTED_MEDIA_TYPE));
  }

  @Test
  void toStringNeverContainsImageBytes() throws Exception {
    PreparedImage prepared = processor.prepare(TestImages.png(8, 8));

    assertThat(prepared.toString()).contains("8x8").doesNotContain("[B@");
  }
}
