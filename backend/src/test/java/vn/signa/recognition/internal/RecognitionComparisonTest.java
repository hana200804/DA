package vn.signa.recognition.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

class RecognitionComparisonTest {

  @Test
  void oneFailedModelKeepsOtherResultsInOrder() throws Exception {
    var client = mock(RecognitionClient.class);
    var limiter = mock(RecognitionRateLimiter.class);
    var lookup = mock(SignInfoLookup.class);
    var service =
        new RecognitionService(
            client, new ImageProcessor(), limiter, null, lookup, null, new SimpleMeterRegistry());
    var prediction = new ServiceDetection.Prediction(new Box(1, 2, 3, 4), "P.102", 0.93);
    when(client.detect(any(), eq("yolov5")))
        .thenReturn(new ServiceDetection("yolov5", "v5", 1, 10, List.of(prediction)));
    when(client.detect(any(), eq("yolov8"))).thenThrow(RecognitionClient.unavailable());
    when(client.detect(any(), eq("detr")))
        .thenReturn(new ServiceDetection("detr", "v1", 1, 20, List.of()));
    when(lookup.lookup(List.of("P.102")))
        .thenReturn(Map.of("P.102", new SignInfoLookup.SignInfo("Cấm đi ngược chiều", null, null)));

    var image = new MockMultipartFile("image", TestImages.png(2560, 1920));
    var result = service.compare(image, "u1");

    var calls = inOrder(client);
    calls.verify(client).detect(any(), eq("yolov5"));
    calls.verify(client).detect(any(), eq("yolov8"));
    calls.verify(client).detect(any(), eq("detr"));
    verify(limiter).acquire(new RateLimit("rl:compare:user:u1", 10));
    assertThat(result.image()).isEqualTo(new DetectResult.ImageSize(2560, 1920));
    assertThat(result.results())
        .extracting(r -> r.model().name())
        .containsExactly("yolov5", "yolov8", "detr");
    assertThat(result.results().getFirst().detections())
        .containsExactly(
            new CompareResult.Detection(new Box(2, 4, 6, 8), "P.102", 0.93, "Cấm đi ngược chiều"));
    assertThat(result.results().get(1).error().message()).isNotBlank();
    assertThat(result.results().getLast().inferenceMs()).isEqualTo(20L);
  }
}
