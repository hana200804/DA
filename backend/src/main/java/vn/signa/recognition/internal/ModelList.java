package vn.signa.recognition.internal;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;
import java.util.Map;

/** Kết quả {@code GET /v1/models} của dịch vụ nhận diện, trả nguyên cho client (docs/07 §5). */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ModelList(String defaultModel, List<ModelEntry> models) {

  /** {@code modelCard} giữ nguyên JSON dịch vụ trả, web đọc {@code val} và {@code test}. */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record ModelEntry(
      String modelName, String modelVersion, int labelMapVersion, Map<String, Object> modelCard) {}
}
