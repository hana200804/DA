package vn.signa.recognition.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.headerDoesNotExist;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.net.ConnectException;
import java.net.http.HttpTimeoutException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.slf4j.MDC;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import vn.signa.shared.error.ApiException;
import vn.signa.shared.error.ErrorCode;
import vn.signa.shared.web.RequestIdFilter;

class RecognitionClientTest {

  static final String MODELS_JSON =
      """
      {"defaultModel":"yolov8","models":[{"modelName":"yolov8","modelVersion":"yolov8s-v1.0",
      "labelMapVersion":1,"defaultConf":0.25,"modelCard":{"val":{"mAP50":0.9},"test":null}}]}
      """;
  static final String DETECT_JSON =
      """
      {"modelName":"yolov8","modelVersion":"yolov8s-v1.0","labelMapVersion":1,"inferenceMs":142,
      "detections":[{"box":{"x":10.5,"y":20,"width":30,"height":40},"signTypeCode":"P.123a",
      "confidence":0.93}]}
      """;

  MutableClock clock = new MutableClock(Instant.parse("2026-11-23T00:00:00Z"));
  MockRestServiceServer server;
  RecognitionClient client;

  @BeforeEach
  void setUp() {
    RestClient.Builder builder = RestClient.builder().baseUrl("http://ai.test");
    server = MockRestServiceServer.bindTo(builder).build();
    client = new RecognitionClient(builder.build(), clock, Duration.ofSeconds(60));
  }

  @AfterEach
  void clearMdc() {
    MDC.clear();
  }

  @Test
  void detectSendsMultipartWithRequestIdAndParsesResult() throws Exception {
    MDC.put(RequestIdFilter.MDC_KEY, "req-42");
    server
        .expect(requestTo("http://ai.test/v1/detect"))
        .andExpect(method(HttpMethod.POST))
        .andExpect(header(RequestIdFilter.HEADER, "req-42"))
        .andExpect(
            header(
                HttpHeaders.CONTENT_TYPE,
                org.hamcrest.Matchers.startsWith(MediaType.MULTIPART_FORM_DATA_VALUE)))
        .andExpect(content().string(org.hamcrest.Matchers.containsString("name=\"image\"")))
        .andExpect(content().string(org.hamcrest.Matchers.containsString("filename=\"image.png\"")))
        .andExpect(content().string(org.hamcrest.Matchers.containsString("name=\"model\"")))
        .andExpect(content().string(org.hamcrest.Matchers.containsString("detr")))
        .andRespond(withSuccess(DETECT_JSON, MediaType.APPLICATION_JSON));

    ServiceDetection result = client.detect(image(), "detr");

    assertThat(result.modelVersion()).isEqualTo("yolov8s-v1.0");
    assertThat(result.inferenceMs()).isEqualTo(142);
    assertThat(result.detections())
        .singleElement()
        .satisfies(p -> {
          assertThat(p.signTypeCode()).isEqualTo("P.123a");
          assertThat(p.box()).isEqualTo(new Box(10.5, 20, 30, 40));
        });
    server.verify();
  }

  @Test
  void defaultModelOmitsModelField() throws Exception {
    server
        .expect(requestTo("http://ai.test/v1/detect"))
        .andExpect(headerDoesNotExist(RequestIdFilter.HEADER))
        .andExpect(
            content()
                .string(
                    org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("name=\"model\""))))
        .andRespond(withSuccess(DETECT_JSON, MediaType.APPLICATION_JSON));

    client.detect(image(), null);

    server.verify();
  }

  @Test
  void serviceBusyBecomesUnavailableWithRetryAfter() throws Exception {
    server
        .expect(requestTo("http://ai.test/v1/detect"))
        .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE).header("Retry-After", "1"));

    assertUnavailable(() -> client.detect(image(), null));
  }

  @Test
  void serverErrorBecomesUnavailable() throws Exception {
    server.expect(requestTo("http://ai.test/v1/detect")).andRespond(withServerError());

    assertUnavailable(() -> client.detect(image(), null));
  }

  @Test
  void timeoutBecomesUnavailable() throws Exception {
    server
        .expect(requestTo("http://ai.test/v1/detect"))
        .andRespond(withException(new HttpTimeoutException("request timed out")));

    assertUnavailable(() -> client.detect(image(), null));
  }

  @Test
  void connectionRefusedBecomesUnavailable() {
    server
        .expect(requestTo("http://ai.test/v1/models"))
        .andRespond(withException(new ConnectException("refused")));

    assertUnavailable(() -> client.models());
  }

  @Test
  void malformedBodyBecomesUnavailable() throws Exception {
    server
        .expect(requestTo("http://ai.test/v1/detect"))
        .andRespond(withSuccess("{oops", MediaType.APPLICATION_JSON));

    assertUnavailable(() -> client.detect(image(), null));
  }

  @ParameterizedTest
  @CsvSource({"400,VALIDATION_ERROR", "413,PAYLOAD_TOO_LARGE", "415,UNSUPPORTED_MEDIA_TYPE"})
  void clientErrorsKeepTheirMeaning(int status, ErrorCode expected) throws Exception {
    server
        .expect(requestTo("http://ai.test/v1/detect"))
        .andRespond(withStatus(HttpStatus.valueOf(status)));

    assertThatThrownBy(() -> client.detect(image(), null))
        .isInstanceOfSatisfying(
            ApiException.class,
            e -> {
              assertThat(e.code()).isEqualTo(expected);
              assertThat(e.retryAfterSeconds()).isNull();
            });
  }

  @Test
  void modelsAreCachedFor60Seconds() {
    server
        .expect(ExpectedCount.twice(), requestTo("http://ai.test/v1/models"))
        .andRespond(withSuccess(MODELS_JSON, MediaType.APPLICATION_JSON));

    ModelList first = client.models();
    clock.advance(Duration.ofSeconds(59));
    ModelList cached = client.models();
    clock.advance(Duration.ofSeconds(1));
    client.models();

    assertThat(cached).isSameAs(first);
    assertThat(first.defaultModel()).isEqualTo("yolov8");
    assertThat(first.models())
        .singleElement()
        .satisfies(m -> {
          assertThat(m.modelVersion()).isEqualTo("yolov8s-v1.0");
          assertThat(m.labelMapVersion()).isEqualTo(1);
          assertThat(m.modelCard()).containsKeys("val", "test");
        });
    server.verify();
  }

  @Test
  void failuresAreNotCached() {
    server
        .expect(requestTo("http://ai.test/v1/models"))
        .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));
    server
        .expect(requestTo("http://ai.test/v1/models"))
        .andRespond(withSuccess(MODELS_JSON, MediaType.APPLICATION_JSON));

    assertUnavailable(() -> client.models());
    assertThat(client.models().defaultModel()).isEqualTo("yolov8");
    server.verify();
  }

  private static PreparedImage image() throws Exception {
    return new ImageProcessor().prepare(TestImages.png(16, 16));
  }

  private static void assertUnavailable(org.assertj.core.api.ThrowableAssert.ThrowingCallable c) {
    assertThatThrownBy(c)
        .isInstanceOfSatisfying(
            ApiException.class,
            e -> {
              assertThat(e.code()).isEqualTo(ErrorCode.RECOGNITION_UNAVAILABLE);
              assertThat(e.retryAfterSeconds()).isEqualTo(1);
            });
  }

  static final class MutableClock extends Clock {
    private Instant now;

    MutableClock(Instant now) {
      this.now = now;
    }

    void advance(Duration d) {
      now = now.plus(d);
    }

    @Override
    public java.time.ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(java.time.ZoneId zone) {
      return this;
    }

    @Override
    public Instant instant() {
      return now;
    }
  }
}
