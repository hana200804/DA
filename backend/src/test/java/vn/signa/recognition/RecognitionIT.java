package vn.signa.recognition;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.stubbing.ServeEvent;
import io.minio.ListObjectsArgs;
import io.minio.MinioClient;
import java.awt.image.BufferedImage;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestClient;
import vn.signa.recognition.internal.TestImages;
import vn.signa.shared.storage.Bucket;
import vn.signa.shared.web.RequestIdFilter;
import vn.signa.support.Containers;
import vn.signa.support.IntegrationTest;

/** Nhận diện qua backend với dịch vụ nhận diện giả lập bằng WireMock (docs/08 IT-B08, B09, B13). */
class RecognitionIT extends IntegrationTest {

  static final WireMockServer AI = new WireMockServer(options().dynamicPort());

  static {
    AI.start();
  }

  static final String DETECT_JSON =
      """
      {"modelName":"yolov8","modelVersion":"yolov8s-v1.0","labelMapVersion":1,"inferenceMs":142,
      "detections":[{"box":{"x":100,"y":50,"width":64,"height":66},"signTypeCode":"P.123a",
      "confidence":0.93}]}
      """;

  @DynamicPropertySource
  static void recognitionService(DynamicPropertyRegistry registry) {
    registry.add("signa.recognition.url", AI::baseUrl);
  }

  @LocalServerPort int port;
  @Autowired StringRedisTemplate redis;

  RestClient http;

  @BeforeEach
  void setUp() {
    AI.resetAll();
    Set<String> keys = redis.keys("rl:recognize:*");
    if (keys != null && !keys.isEmpty()) {
      redis.delete(keys);
    }
    http = RestClient.builder().baseUrl("http://localhost:" + port + "/api/v1").build();
  }

  @AfterAll
  static void stopAi() {
    AI.stop();
  }

  /** IT-B08. */
  @Test
  void slowServiceGives503AfterAbout5Seconds() throws Exception {
    AI.stubFor(post(urlEqualTo("/v1/detect")).willReturn(okJson(DETECT_JSON).withFixedDelay(6000)));

    long start = System.nanoTime();
    Response response = detect("a.png", MediaType.IMAGE_PNG, TestImages.png(64, 48), null);
    Duration elapsed = Duration.ofNanos(System.nanoTime() - start);

    assertThat(response.status()).isEqualTo(503);
    assertThat(response.body()).containsEntry("code", "RECOGNITION_UNAVAILABLE");
    assertThat(response.headers().getFirst(HttpHeaders.RETRY_AFTER)).isEqualTo("1");
    assertThat(elapsed).isBetween(Duration.ofMillis(4500), Duration.ofMillis(5900));
  }

  /** Hàng chờ đầy ở dịch vụ: 503 kèm Retry-After: 1 được chuyển nguyên ý nghĩa. */
  @Test
  void busyServiceGives503() throws Exception {
    AI.stubFor(
        post(urlEqualTo("/v1/detect"))
            .willReturn(aResponse().withStatus(503).withHeader("Retry-After", "1")));

    Response response = detect("a.png", MediaType.IMAGE_PNG, TestImages.png(64, 48), "detr");

    assertThat(response.status()).isEqualTo(503);
    assertThat(response.body()).containsEntry("code", "RECOGNITION_UNAVAILABLE");
    assertThat(response.headers().getFirst(HttpHeaders.RETRY_AFTER)).isEqualTo("1");
  }

  /** IT-B09. */
  @Test
  void recognitionStoresNothingInMinio() throws Exception {
    AI.stubFor(post(urlEqualTo("/v1/detect")).willReturn(okJson(DETECT_JSON)));
    long before = objectCount();

    Response response = detect("a.jpg", MediaType.IMAGE_JPEG, TestImages.jpeg(2000, 1500), null);

    assertThat(response.status()).isEqualTo(200);
    assertThat(objectCount()).isEqualTo(before);
  }

  /** IT-B13. */
  @Test
  void largeImageIsShrunkAndBoxesMapBackToOriginal() throws Exception {
    AI.stubFor(post(urlEqualTo("/v1/detect")).willReturn(okJson(DETECT_JSON)));

    Response response =
        detect("big.png", MediaType.IMAGE_PNG, TestImages.png(2560, 1920), "yolov8");

    assertThat(response.status()).isEqualTo(200);
    assertThat(response.body().get("image")).isEqualTo(Map.of("width", 2560, "height", 1920));
    @SuppressWarnings("unchecked")
    Map<String, Object> detection =
        ((List<Map<String, Object>>) response.body().get("detections")).getFirst();
    @SuppressWarnings("unchecked")
    Map<String, Number> box = (Map<String, Number>) detection.get("box");
    assertThat(box.get("x").doubleValue()).isEqualTo(200.0);
    assertThat(box.get("y").doubleValue()).isEqualTo(100.0);
    assertThat(box.get("width").doubleValue()).isEqualTo(128.0);
    assertThat(box.get("height").doubleValue()).isEqualTo(132.0);
    assertThat(detection).containsEntry("signTypeCode", "P.123a");

    List<ServeEvent> calls = AI.getAllServeEvents();
    assertThat(calls).hasSize(1);
    byte[] sent = calls.getFirst().getRequest().getPart("image").getBody().asBytes();
    BufferedImage sentImage = TestImages.decode(sent);
    assertThat(sentImage.getWidth()).isEqualTo(1280);
    assertThat(sentImage.getHeight()).isEqualTo(960);
    assertThat(calls.getFirst().getRequest().getPart("model").getBody().asString())
        .isEqualTo("yolov8");
  }

  @Test
  void the31stCallFromOneIpWithinAMinuteIsRateLimited() throws Exception {
    AI.stubFor(post(urlEqualTo("/v1/detect")).willReturn(okJson(DETECT_JSON)));
    byte[] png = TestImages.png(16, 16);

    for (int i = 1; i <= 30; i++) {
      assertThat(detect("a.png", MediaType.IMAGE_PNG, png, null).status())
          .as("lần %d", i)
          .isEqualTo(200);
    }
    Response limited = detect("a.png", MediaType.IMAGE_PNG, png, null);

    assertThat(limited.status()).isEqualTo(429);
    assertThat(limited.body()).containsEntry("code", "RATE_LIMITED");
    assertThat(Integer.parseInt(limited.headers().getFirst(HttpHeaders.RETRY_AFTER)))
        .isBetween(1, 60);
    AI.verify(30, postRequestedFor(urlEqualTo("/v1/detect")));
  }

  @Test
  void textRenamedToJpgIsUnsupported() {
    Response response =
        detect(
            "photo.jpg",
            MediaType.IMAGE_JPEG,
            "không phải ảnh".getBytes(StandardCharsets.UTF_8),
            null);

    assertThat(response.status()).isEqualTo(415);
    assertThat(response.body()).containsEntry("code", "UNSUPPORTED_MEDIA_TYPE");
    AI.verify(0, postRequestedFor(urlEqualTo("/v1/detect")));
  }

  @Test
  void modelsArePassedThroughWithRequestId() {
    AI.stubFor(
        get(urlEqualTo("/v1/models"))
            .willReturn(
                okJson(
                    """
                    {"defaultModel":"yolov8","models":[{"modelName":"yolov8",
                    "modelVersion":"yolov8s-v1.0","labelMapVersion":1,"defaultConf":0.25,
                    "modelCard":{"val":{"mAP50":0.9},"test":{"mAP50":0.88}}}]}
                    """)));

    Map<?, ?> body =
        http.get()
            .uri("/recognition/models")
            .header(RequestIdFilter.HEADER, "it-models-1")
            .retrieve()
            .body(Map.class);

    assertThat(body.get("defaultModel")).isEqualTo("yolov8");
    assertThat(body.get("models").toString()).contains("yolov8s-v1.0", "mAP50");
    AI.verify(
        getRequestedFor(urlEqualTo("/v1/models"))
            .withHeader(RequestIdFilter.HEADER, equalTo("it-models-1")));
  }

  private Response detect(String filename, MediaType type, byte[] data, String model) {
    MultipartBodyBuilder parts = new MultipartBodyBuilder();
    parts
        .part(
            "image",
            new ByteArrayResource(data) {
              @Override
              public String getFilename() {
                return filename;
              }
            })
        .contentType(type);
    if (model != null) {
      parts.part("model", model);
    }
    return http.post()
        .uri("/recognition/detect")
        .contentType(MediaType.MULTIPART_FORM_DATA)
        .body(parts.build())
        .exchange((request, response) -> {
          @SuppressWarnings("unchecked")
          Map<String, Object> body = response.bodyTo(Map.class);
          return new Response(response.getStatusCode().value(), response.getHeaders(), body);
        });
  }

  private static long objectCount() throws Exception {
    MinioClient minio =
        MinioClient.builder()
            .endpoint(Containers.MINIO.getS3URL())
            .credentials(Containers.MINIO.getUserName(), Containers.MINIO.getPassword())
            .build();
    long count = 0;
    for (Bucket bucket : Bucket.values()) {
      for (var ignored :
          minio.listObjects(
              ListObjectsArgs.builder().bucket(bucket.bucketName()).recursive(true).build())) {
        count++;
      }
    }
    return count;
  }

  record Response(int status, HttpHeaders headers, Map<String, Object> body) {}
}
