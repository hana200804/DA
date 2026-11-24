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
 */
public record PreparedImage(
    byte[] payload,
    MediaType mediaType,
    String filename,
    int width,
    int height,
    int sentWidth,
    int sentHeight) {

  /** Quy đổi box dịch vụ trả (theo ảnh gửi) về toạ độ ảnh nhận từ ứng dụng. */
  public Box toOriginal(Box sent) {
    return sent.scale((double) width / sentWidth, (double) height / sentHeight);
  }

  @Override
  public String toString() {
    // Không bao giờ in byte ảnh ra log.
    return "PreparedImage[%s %dx%d -> %dx%d, %d bytes]"
        .formatted(mediaType, width, height, sentWidth, sentHeight, payload.length);
  }
}
