package vn.signa.recognition.internal;

import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import vn.signa.shared.error.ApiException;
import vn.signa.shared.error.ErrorCode;
import vn.signa.shared.image.ImageFormat;
import vn.signa.shared.web.RequestIdFilter;

/**
 * Gọi dịch vụ nhận diện Python qua HTTP (ADR-0005, docs/07 §7). Mọi lỗi phía dịch vụ, timeout hay
 * lỗi kết nối đều thành {@code 503 RECOGNITION_UNAVAILABLE} kèm {@code Retry-After: 1}.
 */
@Component
public class RecognitionClient {

  private static final Logger log = LoggerFactory.getLogger(RecognitionClient.class);
  static final int RETRY_AFTER_SECONDS = 1;

  private final RestClient rest;
  private final Clock clock;
  private final Duration modelsCacheTtl;
  private volatile CachedModels cached;

  @Autowired
  public RecognitionClient(RecognitionProperties properties, Clock clock) {
    this(restClient(properties), clock, properties.modelsCacheTtl());
  }

  RecognitionClient(RestClient rest, Clock clock, Duration modelsCacheTtl) {
    this.rest = rest;
    this.clock = clock;
    this.modelsCacheTtl = modelsCacheTtl;
  }

  static RestClient restClient(RecognitionProperties properties) {
    HttpClient http =
        HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(properties.connectTimeout())
            .build();
    JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(http);
    factory.setReadTimeout(properties.readTimeout());
    return RestClient.builder().baseUrl(properties.url()).requestFactory(factory).build();
  }

  /** Danh sách mô hình, cache trong bộ nhớ {@code modelsCacheTtl}. Lỗi không được cache. */
  public ModelList models() {
    Instant now = clock.instant();
    CachedModels current = cached;
    if (current != null && now.isBefore(current.expiresAt())) {
      return current.models();
    }
    ModelList fresh =
        call(() ->
            rest.get()
                .uri("/v1/models")
                .headers(RecognitionClient::addRequestId)
                .exchange((request, response) -> {
                  throwIfError(response.getStatusCode());
                  return response.bodyTo(ModelList.class);
                }));
    cached = new CachedModels(fresh, now.plus(modelsCacheTtl));
    return fresh;
  }

  /**
   * Gửi ảnh cho {@code /v1/detect}.
   *
   * @param model {@code yolov5}, {@code yolov8}, {@code detr}, hoặc {@code null} để dùng mặc định
   */
  public ServiceDetection detect(PreparedImage image, String model) {
    MultipartBodyBuilder parts = new MultipartBodyBuilder();
    parts.part("image", namedBytes(image)).contentType(image.mediaType());
    if (model != null) {
      parts.part("model", model);
    }
    return call(() ->
        rest.post()
            .uri("/v1/detect")
            .headers(RecognitionClient::addRequestId)
            .contentType(MediaType.MULTIPART_FORM_DATA)
            .body(parts.build())
            .exchange((request, response) -> {
              throwIfError(response.getStatusCode());
              return response.bodyTo(ServiceDetection.class);
            }));
  }

  private static <T> T call(Supplier<T> exchange) {
    T body;
    try {
      body = exchange.get();
    } catch (RestClientException e) {
      // Timeout, lỗi kết nối, body hỏng. Không log nội dung request (có ảnh).
      log.warn("Dịch vụ nhận diện không phản hồi: {}", e.getClass().getSimpleName());
      throw unavailable();
    }
    if (body == null) {
      log.warn("Dịch vụ nhận diện trả body rỗng");
      throw unavailable();
    }
    return body;
  }

  private static void throwIfError(HttpStatusCode status) {
    if (status.is2xxSuccessful()) {
      return;
    }
    throw switch (status.value()) {
      case 400 ->
          new ApiException(ErrorCode.VALIDATION_ERROR, "Dịch vụ nhận diện từ chối ảnh hoặc model");
      case 413 -> new ApiException(ErrorCode.PAYLOAD_TOO_LARGE, "Ảnh nhận diện quá lớn");
      case 415 -> ImageFormat.unsupported();
      default -> {
        log.warn("Dịch vụ nhận diện trả HTTP {}", status.value());
        yield unavailable();
      }
    };
  }

  static ApiException unavailable() {
    return new ApiException(
        ErrorCode.RECOGNITION_UNAVAILABLE,
        ErrorCode.RECOGNITION_UNAVAILABLE.defaultMessage(),
        List.of(),
        RETRY_AFTER_SECONDS);
  }

  private static void addRequestId(HttpHeaders headers) {
    String requestId = MDC.get(RequestIdFilter.MDC_KEY);
    if (requestId != null) {
      headers.set(RequestIdFilter.HEADER, requestId);
    }
  }

  private static ByteArrayResource namedBytes(PreparedImage image) {
    return new ByteArrayResource(image.payload()) {
      @Override
      public String getFilename() {
        return image.filename();
      }
    };
  }

  private record CachedModels(ModelList models, Instant expiresAt) {}
}
