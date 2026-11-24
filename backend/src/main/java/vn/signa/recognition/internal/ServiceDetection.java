package vn.signa.recognition.internal;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

/** Kết quả {@code POST /v1/detect} của dịch vụ nhận diện; box theo ảnh đã gửi. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ServiceDetection(
    String modelName,
    String modelVersion,
    int labelMapVersion,
    long inferenceMs,
    List<Prediction> detections) {

  @JsonIgnoreProperties(ignoreUnknown = true)
  public record Prediction(Box box, String signTypeCode, double confidence) {}
}
