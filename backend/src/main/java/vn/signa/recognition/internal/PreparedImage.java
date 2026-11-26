package vn.signa.recognition.internal;

import org.springframework.http.MediaType;

/**
 * Ảnh sẵn sàng gửi cho dịch vụ nhận diện, chỉ nằm trong bộ nhớ.
 *
 * @param payload byte gửi đi: ảnh gốc, hoặc JPEG đã thu nhỏ
 * @param width chiều rộng ảnh nhận từ ứng dụng
 * @param height chiều cao ảnh nhận từ ứng dụng
 * @param sentWidth chiều rộng ảnh gửi cho dịch vụ
 * @param sentHeight chiều cao ảnh gửi cho dịch vụ
 * @param contentWidth chiều rộng phần ảnh đã thu nhỏ
 * @param contentHeight chiều cao phần ảnh đã thu nhỏ
 */
public record PreparedImage(
    byte[] payload,
    MediaType mediaType,
    String filename,
    int width,
    int height,
    int sentWidth,
    int sentHeight,
    int contentWidth,
    int contentHeight) {

  public PreparedImage(
      byte[] payload,
      MediaType mediaType,
      String filename,
      int width,
      int height,
      int sentWidth,
      int sentHeight) {
    this(payload, mediaType, filename, width, height, sentWidth, sentHeight, sentWidth, sentHeight);
  }

  boolean hasPadding() {
    return sentWidth != contentWidth || sentHeight != contentHeight;
  }

  /** Quy đổi box dịch vụ trả (theo ảnh gửi) về toạ độ ảnh nhận từ ứng dụng. */
  public Box toOriginal(Box sent) {
    Box content = sent;
    if (hasPadding()) {
      double left = Math.max(0, Math.min(sent.x(), contentWidth));
      double top = Math.max(0, Math.min(sent.y(), contentHeight));
      double right = Math.min(sent.x() + sent.width(), contentWidth);
      double bottom = Math.min(sent.y() + sent.height(), contentHeight);
      content = new Box(left, top, Math.max(0, right - left), Math.max(0, bottom - top));
    }
    return content.scale((double) width / contentWidth, (double) height / contentHeight);
  }

  @Override
  public String toString() {
    // Không bao giờ in byte ảnh ra log.
    return "PreparedImage[%s %dx%d -> %dx%d, %d bytes]"
        .formatted(mediaType, width, height, sentWidth, sentHeight, payload.length);
  }
}
