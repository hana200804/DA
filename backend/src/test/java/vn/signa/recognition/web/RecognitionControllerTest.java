package vn.signa.recognition.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import vn.signa.identity.web.WithApiSecurity;
import vn.signa.recognition.internal.Box;
import vn.signa.recognition.internal.CallerIdentity;
import vn.signa.recognition.internal.ImageProcessor;
import vn.signa.recognition.internal.ModelList;
import vn.signa.recognition.internal.RateLimit;
import vn.signa.recognition.internal.RecognitionClient;
import vn.signa.recognition.internal.RecognitionProperties;
import vn.signa.recognition.internal.RecognitionRateLimiter;
import vn.signa.recognition.internal.RecognitionService;
import vn.signa.recognition.internal.ServiceDetection;
import vn.signa.recognition.internal.SignInfoLookup;
import vn.signa.recognition.internal.TestImages;
import vn.signa.shared.error.ApiException;
import vn.signa.shared.error.ErrorCode;
import vn.signa.shared.web.GlobalExceptionHandler;
import vn.signa.shared.web.RequestIdFilter;

@WebMvcTest(controllers = RecognitionController.class)
@WithApiSecurity
@Import({
  RecognitionService.class,
  ImageProcessor.class,
  GlobalExceptionHandler.class,
  RequestIdFilter.class,
  RecognitionControllerTest.Metrics.class
})
class RecognitionControllerTest {

  @Autowired MockMvc mvc;
  @Autowired MeterRegistry meters;

  @MockitoBean RecognitionClient client;
  @MockitoBean RecognitionRateLimiter limiter;
  @MockitoBean CallerIdentity caller;
  @MockitoBean SignInfoLookup signInfo;

  @TestConfiguration
  @EnableConfigurationProperties(RecognitionProperties.class)
  static class Metrics {
    @Bean
    MeterRegistry meterRegistry() {
      return new SimpleMeterRegistry();
    }
  }

  @Test
  void modelsArePassedThrough() throws Exception {
    when(client.models())
        .thenReturn(
            new ModelList(
                "yolov8",
                List.of(
                    new ModelList.ModelEntry(
                        "yolov8",
                        "yolov8s-v1.0",
                        1,
                        Map.of("val", Map.of("mAP50", 0.91), "test", Map.of("mAP50", 0.88))))));

    mvc.perform(get("/api/v1/recognition/models"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.defaultModel").value("yolov8"))
        .andExpect(jsonPath("$.models[0].modelName").value("yolov8"))
        .andExpect(jsonPath("$.models[0].modelVersion").value("yolov8s-v1.0"))
        .andExpect(jsonPath("$.models[0].labelMapVersion").value(1))
        .andExpect(jsonPath("$.models[0].modelCard.val.mAP50").value(0.91))
        .andExpect(jsonPath("$.models[0].modelCard.test.mAP50").value(0.88));
  }

  @Test
  void detectScalesBoxesAndAddsSignInfo() throws Exception {
    when(caller.userId()).thenReturn(Optional.empty());
    when(client.detect(any(), eq("yolov8")))
        .thenReturn(
            new ServiceDetection(
                "yolov8",
                "yolov8s-v1.0",
                1,
                142,
                List.of(
                    new ServiceDetection.Prediction(new Box(100, 50, 64, 66), "P.123a", 0.93),
                    new ServiceDetection.Prediction(new Box(0, 0, 10, 10), "W.999", 0.4))));
    when(signInfo.lookup(anyCollection()))
        .thenReturn(
            Map.of(
                "P.123a",
                new SignInfoLookup.SignInfo("Cấm rẽ trái", "Cấm các loại xe rẽ trái", "/i.png")));

    mvc.perform(
            multipart("/api/v1/recognition/detect")
                .file(image("photo.png", TestImages.png(2560, 1920)))
                .param("model", "yolov8"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.model.name").value("yolov8"))
        .andExpect(jsonPath("$.model.version").value("yolov8s-v1.0"))
        .andExpect(jsonPath("$.model.labelMapVersion").value(1))
        .andExpect(jsonPath("$.image.width").value(2560))
        .andExpect(jsonPath("$.image.height").value(1920))
        .andExpect(jsonPath("$.inferenceMs").value(142))
        .andExpect(jsonPath("$.detections[0].box.x").value(200.0))
        .andExpect(jsonPath("$.detections[0].box.y").value(100.0))
        .andExpect(jsonPath("$.detections[0].box.width").value(128.0))
        .andExpect(jsonPath("$.detections[0].box.height").value(132.0))
        .andExpect(jsonPath("$.detections[0].signTypeCode").value("P.123a"))
        .andExpect(jsonPath("$.detections[0].confidence").value(0.93))
        .andExpect(jsonPath("$.detections[0].name").value("Cấm rẽ trái"))
        .andExpect(jsonPath("$.detections[0].description").value("Cấm các loại xe rẽ trái"))
        .andExpect(jsonPath("$.detections[0].iconUrl").value("/i.png"))
        .andExpect(jsonPath("$.detections[1].name").doesNotExist());

    verify(limiter).acquire(new RateLimit("rl:recognize:ip:127.0.0.1", 30));
    assertThat(
            meters
                .get("signa.recognition.duration")
                .tag("model", "yolov8")
                .tag("outcome", "success")
                .timer()
                .count())
        .isEqualTo(1);
  }

  @Test
  void omittedModelUsesServiceDefault() throws Exception {
    when(client.detect(any(), isNull()))
        .thenReturn(new ServiceDetection("detr", "detr-r50-v1", 1, 900, List.of()));

    mvc.perform(
            multipart("/api/v1/recognition/detect").file(image("a.jpg", TestImages.jpeg(64, 48))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.model.name").value("detr"))
        .andExpect(jsonPath("$.image.width").value(64))
        .andExpect(jsonPath("$.detections").isEmpty());
  }

  @Test
  void signedInUserIsLimitedByAccount() throws Exception {
    when(caller.userId()).thenReturn(Optional.of("user-1"));
    when(client.detect(any(), isNull()))
        .thenReturn(new ServiceDetection("yolov8", "v", 1, 1, List.of()));

    mvc.perform(multipart("/api/v1/recognition/detect").file(image("a.png", TestImages.png(8, 8))))
        .andExpect(status().isOk());

    verify(limiter).acquire(new RateLimit("rl:recognize:user:user-1", 10));
  }

  @Test
  void unknownModelIsRejected() throws Exception {
    mvc.perform(
            multipart("/api/v1/recognition/detect")
                .file(image("a.png", TestImages.png(8, 8)))
                .param("model", "yolo11"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
        .andExpect(jsonPath("$.details[0].field").value("model"));

    verify(client, never()).detect(any(), any());
  }

  @Test
  void textRenamedToJpgIsUnsupported() throws Exception {
    MockMultipartFile fake =
        new MockMultipartFile(
            "image",
            "photo.jpg",
            MediaType.IMAGE_JPEG_VALUE,
            "đây là văn bản".getBytes(StandardCharsets.UTF_8));

    mvc.perform(multipart("/api/v1/recognition/detect").file(fake))
        .andExpect(status().isUnsupportedMediaType())
        .andExpect(jsonPath("$.code").value("UNSUPPORTED_MEDIA_TYPE"));

    verify(client, never()).detect(any(), any());
  }

  @Test
  void imageOver10MbIsTooLarge() throws Exception {
    byte[] big = new byte[(int) ImageProcessor.MAX_BYTES + 1];
    System.arraycopy(TestImages.jpeg(8, 8), 0, big, 0, 3);

    mvc.perform(multipart("/api/v1/recognition/detect").file(image("big.jpg", big)))
        .andExpect(status().isContentTooLarge())
        .andExpect(jsonPath("$.code").value("PAYLOAD_TOO_LARGE"));
  }

  @Test
  void missingImageIsValidationError() throws Exception {
    mvc.perform(multipart("/api/v1/recognition/detect").param("model", "yolov8"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
  }

  @Test
  void rateLimitedCallerGets429WithRetryAfter() throws Exception {
    doThrow(ApiException.rateLimited(42)).when(limiter).acquire(any());

    mvc.perform(multipart("/api/v1/recognition/detect").file(image("a.png", TestImages.png(8, 8))))
        .andExpect(status().isTooManyRequests())
        .andExpect(header().string("Retry-After", "42"))
        .andExpect(jsonPath("$.code").value("RATE_LIMITED"));

    verify(client, never()).detect(any(), any());
  }

  @Test
  void serviceUnavailableGives503WithRetryAfterAndMetric() throws Exception {
    when(client.detect(any(), eq("detr")))
        .thenThrow(
            new ApiException(
                ErrorCode.RECOGNITION_UNAVAILABLE,
                ErrorCode.RECOGNITION_UNAVAILABLE.defaultMessage(),
                List.of(),
                1));

    mvc.perform(
            multipart("/api/v1/recognition/detect")
                .file(image("a.png", TestImages.png(8, 8)))
                .param("model", "detr"))
        .andExpect(status().isServiceUnavailable())
        .andExpect(header().string("Retry-After", "1"))
        .andExpect(jsonPath("$.code").value("RECOGNITION_UNAVAILABLE"));

    assertThat(
            meters
                .get("signa.recognition.duration")
                .tag("model", "detr")
                .tag("outcome", "unavailable")
                .timer()
                .count())
        .isEqualTo(1);
  }

  private static MockMultipartFile image(String filename, byte[] data) {
    return new MockMultipartFile("image", filename, MediaType.APPLICATION_OCTET_STREAM_VALUE, data);
  }
}
