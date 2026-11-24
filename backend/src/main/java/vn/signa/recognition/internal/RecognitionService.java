package vn.signa.recognition.internal;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import vn.signa.shared.error.ApiException;
import vn.signa.shared.error.ErrorCode;
import vn.signa.shared.error.FieldIssue;

/**
 * Nhận diện ảnh trên server (FR-02, docs/03 §6.1). Ảnh chỉ nằm trong bộ nhớ; không có đường nào ghi
 * ảnh ra MinIO hay đĩa (NFR-09).
 */
@Service
public class RecognitionService {

  public static final Set<String> MODELS = Set.of("yolov5", "yolov8", "detr");
  private static final List<String> COMPARISON_MODELS = List.of("yolov5", "yolov8", "detr");
  private static final int COMPARISONS_PER_MINUTE = 10;
  static final String METRIC = "signa.recognition.duration";

  private final RecognitionClient client;
  private final ImageProcessor images;
  private final RecognitionRateLimiter limiter;
  private final CallerIdentity caller;
  private final SignInfoLookup signInfo;
  private final RecognitionProperties properties;
  private final MeterRegistry meters;

  public RecognitionService(
      RecognitionClient client,
      ImageProcessor images,
      RecognitionRateLimiter limiter,
      CallerIdentity caller,
      SignInfoLookup signInfo,
      RecognitionProperties properties,
      MeterRegistry meters) {
    this.client = client;
    this.images = images;
    this.limiter = limiter;
    this.caller = caller;
    this.signInfo = signInfo;
    this.properties = properties;
    this.meters = meters;
  }

  public ModelList models() {
    return client.models();
  }

  /**
   * Thứ tự: giới hạn tần suất, model, kích thước, định dạng, gọi dịch vụ, quy đổi box, bổ sung
   * thông tin biển.
   *
   * @param model {@code yolov5}, {@code yolov8}, {@code detr}, hoặc trống để dùng mặc định
   * @param clientIp {@code request.getRemoteAddr()}, đã qua {@code forward-headers-strategy}
   */
  public DetectResult detect(MultipartFile upload, String model, String clientIp) {
    limiter.acquire(
        RateLimit.forCaller(
            caller.userId(), clientIp, properties.guestPerMinute(), properties.userPerMinute()));
    String selected = normalizeModel(model);
    PreparedImage image = prepare(upload);
    ServiceDetection detection = timed(selected, () -> client.detect(image, selected));
    return new DetectResult(
        new DetectResult.ModelRef(
            detection.modelName(), detection.modelVersion(), detection.labelMapVersion()),
        new DetectResult.ImageSize(image.width(), image.height()),
        detection.inferenceMs(),
        detections(image, detection));
  }

  public CompareResult compare(MultipartFile upload, String userId) {
    limiter.acquire(new RateLimit("rl:compare:user:" + userId, COMPARISONS_PER_MINUTE));
    PreparedImage image = prepare(upload);
    List<CompareResult.ModelResult> results =
        COMPARISON_MODELS.stream().map(model -> compareModel(image, model)).toList();
    return new CompareResult(new DetectResult.ImageSize(image.width(), image.height()), results);
  }

  private CompareResult.ModelResult compareModel(PreparedImage image, String model) {
    ServiceDetection response;
    try {
      response =
          timed(
              model,
              () -> {
                ServiceDetection detected = client.detect(image, model);
                if (!model.equals(detected.modelName())
                    || (detected.detections() != null
                        && detected.detections().stream()
                            .anyMatch(
                                p -> p == null || p.box() == null || p.signTypeCode() == null))) {
                  throw RecognitionClient.unavailable();
                }
                return detected;
              });
    } catch (ApiException e) {
      return new CompareResult.ModelResult(
          new CompareResult.ModelRef(model, null, null),
          null,
          null,
          new CompareResult.ModelError(e.getMessage()));
    }
    return new CompareResult.ModelResult(
        new CompareResult.ModelRef(model, response.modelVersion(), response.labelMapVersion()),
        response.inferenceMs(),
        detections(image, response).stream()
            .map(d ->
                new CompareResult.Detection(d.box(), d.signTypeCode(), d.confidence(), d.name()))
            .toList(),
        null);
  }

  private PreparedImage prepare(MultipartFile upload) {
    if (upload.getSize() > ImageProcessor.MAX_BYTES) {
      throw new ApiException(ErrorCode.PAYLOAD_TOO_LARGE, "Ảnh nhận diện tối đa 10 MB");
    }
    return images.prepare(bytes(upload));
  }

  private List<DetectResult.Detection> detections(PreparedImage image, ServiceDetection detection) {
    List<ServiceDetection.Prediction> predictions =
        detection.detections() == null ? List.of() : detection.detections();
    Map<String, SignInfoLookup.SignInfo> info =
        predictions.isEmpty()
            ? Map.of()
            : signInfo.lookup(
                predictions.stream().map(ServiceDetection.Prediction::signTypeCode).toList());
    return predictions.stream()
        .map(p -> {
          SignInfoLookup.SignInfo sign = info.get(p.signTypeCode());
          return new DetectResult.Detection(
              image.toOriginal(p.box()),
              p.signTypeCode(),
              p.confidence(),
              sign == null ? null : sign.name(),
              sign == null ? null : sign.description(),
              sign == null ? null : sign.iconUrl());
        })
        .toList();
  }

  private ServiceDetection timed(String selected, Supplier<ServiceDetection> call) {
    String modelTag = selected == null ? "default" : selected;
    String outcome = "unavailable";
    Timer.Sample sample = Timer.start(meters);
    try {
      ServiceDetection result = call.get();
      if (MODELS.contains(result.modelName())) {
        modelTag = result.modelName();
      }
      outcome = "success";
      return result;
    } catch (ApiException e) {
      outcome = e.code() == ErrorCode.RECOGNITION_UNAVAILABLE ? "unavailable" : "rejected";
      throw e;
    } finally {
      sample.stop(
          Timer.builder(METRIC)
              .description("Thời gian gọi dịch vụ nhận diện")
              .tag("model", modelTag)
              .tag("outcome", outcome)
              .register(meters));
    }
  }

  static String normalizeModel(String model) {
    if (model == null || model.isBlank()) {
      return null;
    }
    if (!MODELS.contains(model)) {
      throw new ApiException(
          ErrorCode.VALIDATION_ERROR,
          ErrorCode.VALIDATION_ERROR.defaultMessage(),
          List.of(new FieldIssue("model", "phải là yolov5, yolov8 hoặc detr")),
          null);
    }
    return model;
  }

  private static byte[] bytes(MultipartFile upload) {
    try {
      return upload.getBytes();
    } catch (IOException e) {
      throw new ApiException(ErrorCode.VALIDATION_ERROR, "Không đọc được ảnh gửi lên");
    }
  }
}
