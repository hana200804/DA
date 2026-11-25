package vn.signa.recognition.internal;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/** Kết quả so sánh trên cùng một ảnh gốc (FR-37). */
public record CompareResult(DetectResult.ImageSize image, List<ModelResult> results) {

  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record ModelResult(
      ModelRef model, Long inferenceMs, List<Detection> detections, ModelError error) {}

  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record ModelRef(String name, String version, Integer labelMapVersion) {}

  public record Detection(Box box, String signTypeCode, double confidence, String name) {}

  public record ModelError(String message) {}
}
