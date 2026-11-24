package vn.signa.sign.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.databind.json.JsonMapper;
import vn.signa.shared.error.ApiException;
import vn.signa.shared.error.ErrorCode;
import vn.signa.shared.error.FieldIssue;
import vn.signa.sign.clustering.GeoPoint;
import vn.signa.sign.clustering.SubmissionFlag;

class SubmissionMetadataTest {

  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final String VALID =
      """
      {"clientId":"57240cd0-661c-42e5-8477-f0c204ef8d29","signTypeCode":"P.102",
       "confidence":1,"location":{"lat":-90,"lng":180},"headingDeg":null,"source":"PHOTO",
       "flags":["SPLIT_VOTE","USER_EDITED"],
       "model":{"name":"yolov8n","version":"1.0","labelMapVersion":1},
       "capturedAt":"2026-11-24T00:00:00Z"}
      """;

  @Test
  void acceptsInclusiveBoundsAndNullHeading() {
    var metadata = read(VALID);
    assertThat(metadata.location()).isEqualTo(new GeoPoint(-90, 180));
    assertThat(metadata.confidence()).isEqualByComparingTo("1");
    assertThat(metadata.headingDeg()).isNull();
    assertThat(metadata.flags()).containsExactlyInAnyOrder(SubmissionFlag.values());
    assertThat(metadata.capturedAt()).isEqualTo(Instant.parse("2026-11-24T00:00:00Z"));
    assertThat(read(VALID.replace("null", "359")).headingDeg()).isEqualTo(359);
  }

  @ParameterizedTest
  @MethodSource("invalidFields")
  void reportsInvalidField(String from, String to, String field) {
    assertThatThrownBy(() -> read(VALID.replace(from, to)))
        .isInstanceOfSatisfying(
            ApiException.class,
            error -> {
              assertThat(error.code()).isEqualTo(ErrorCode.VALIDATION_ERROR);
              assertThat(error.details()).extracting(FieldIssue::field).containsExactly(field);
            });
  }

  static Stream<Arguments> invalidFields() {
    return Stream.of(
        Arguments.of("57240cd0-661c-42e5-8477-f0c204ef8d29", "1-1-1-1-1", "clientId"),
        Arguments.of("P.102", "", "signTypeCode"),
        Arguments.of("\"confidence\":1", "\"confidence\":1.0000000000000000001", "confidence"),
        Arguments.of("\"confidence\":1", "\"confidence\":\"0.5\"", "confidence"),
        Arguments.of("\"lat\":-90", "\"lat\":-91", "location.lat"),
        Arguments.of("\"lng\":180", "\"lng\":181", "location.lng"),
        Arguments.of("null", "360", "headingDeg"),
        Arguments.of("null", "359.5", "headingDeg"),
        Arguments.of("PHOTO", "OTHER", "source"),
        Arguments.of("SPLIT_VOTE", "OTHER", "flags[0]"),
        Arguments.of("yolov8n", " ", "model.name"),
        Arguments.of("1.0\"", "\"", "model.version"),
        Arguments.of("\"labelMapVersion\":1", "\"labelMapVersion\":\"1\"", "model.labelMapVersion"),
        Arguments.of("2026-11-24T00:00:00Z", "2026-11-24", "capturedAt"));
  }

  @Test
  void reportsMalformedJsonAndMultipleValues() {
    for (String invalid : new String[] {"{", "null", "[]", VALID + "{}"}) {
      assertThatThrownBy(() -> read(invalid))
          .isInstanceOfSatisfying(
              ApiException.class,
              error ->
                  assertThat(error.details())
                      .extracting(FieldIssue::field)
                      .containsExactly("metadata"));
    }
  }

  private static SubmissionMetadata read(String json) {
    return SubmissionMetadata.read(JSON, json.getBytes(StandardCharsets.UTF_8));
  }
}
