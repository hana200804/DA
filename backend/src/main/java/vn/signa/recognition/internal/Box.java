package vn.signa.recognition.internal;

/** Box theo pixel, gốc toạ độ ở góc trên trái (docs/07 §5). */
public record Box(double x, double y, double width, double height) {

  /** Quy đổi box từ ảnh gửi cho dịch vụ về ảnh gốc theo tỉ lệ từng trục. */
  public Box scale(double sx, double sy) {
    return new Box(x * sx, y * sy, width * sx, height * sy);
  }
}
