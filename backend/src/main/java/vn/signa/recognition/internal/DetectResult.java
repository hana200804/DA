package vn.signa.recognition.internal;

import java.util.List;

/** Response của {@code POST /api/v1/recognition/detect} (docs/07 §5). */
public record DetectResult(
    ModelRef model, ImageSize image, long inferenceMs, List<Detection> detections) {

  public record ModelRef(String name, String version, int labelMapVersion) {}

  /** Kích thước ảnh nhận từ ứng dụng, trước khi thu nhỏ. */
  public record ImageSize(int width, int height) {}

  /** {@code box} theo toạ độ ảnh nhận từ ứng dụng. */
  public record Detection(
      Box box,
      String signTypeCode,
      double confidence,
      String name,
      String description,
      String iconUrl) {}
}
