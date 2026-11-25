package vn.signa.sign.internal.feedback;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HexFormat;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;
import vn.signa.shared.error.ApiException;
import vn.signa.shared.error.ErrorCode;
import vn.signa.sign.internal.feedback.ReportService.Reason;

class ReportInputTest {

  private final JsonMapper json = JsonMapper.builder().build();

  @ParameterizedTest
  @ValueSource(
      strings = {
        "null",
        "[]",
        "{}",
        "{\"reason\":\"UNKNOWN\"}",
        "{\"reason\":0}",
        "{\"reason\":\"OTHER\"}",
        "{\"reason\":\"OTHER\",\"description\":\" \"}",
        "{\"reason\":\"OTHER\",\"description\":123}"
      })
  void rejectsInvalidMetadata(String body) {
    assertThatThrownBy(() -> ReportMetadata.read(json.readTree(body)))
        .isInstanceOfSatisfying(
            ApiException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.VALIDATION_ERROR));
  }

  @Test
  void descriptionIsOptionalExceptForOtherAndHasAThousandCharacterLimit() {
    assertThat(ReportMetadata.read(json.readTree("{\"reason\":\"NOT_EXIST\"}")))
        .isEqualTo(new ReportMetadata(Reason.NOT_EXIST, null));
    String max = "ễ".repeat(1000);
    assertThat(
            ReportMetadata.read(
                json.createObjectNode().put("reason", "OTHER").put("description", max)))
        .isEqualTo(new ReportMetadata(Reason.OTHER, max));
    var tooLong = json.createObjectNode().put("reason", "OTHER").put("description", max + "ễ");
    assertThatThrownBy(() -> ReportMetadata.read(tooLong))
        .isInstanceOfSatisfying(
            ApiException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.VALIDATION_ERROR));
  }

  @ParameterizedTest
  @CsvSource({"ffd8ff, JPEG", "89504e470d0a1a0a, PNG", "524946462400000057454250, WEBP"})
  void detectsAllowedMagicBytes(String hex, ReportPhoto expected) {
    assertThat(ReportPhoto.detect(HexFormat.of().parseHex(hex))).isEqualTo(expected);
  }

  @Test
  void rejectsInvalidOrOversizedPhoto() {
    assertThatThrownBy(() -> ReportPhoto.detect(new byte[] {1, 2, 3}))
        .isInstanceOfSatisfying(
            ApiException.class,
            e -> assertThat(e.code()).isEqualTo(ErrorCode.UNSUPPORTED_MEDIA_TYPE));
    assertThatThrownBy(() -> ReportPhoto.detect(new byte[ReportPhoto.MAX_BYTES + 1]))
        .isInstanceOfSatisfying(
            ApiException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.PAYLOAD_TOO_LARGE));
  }
}
