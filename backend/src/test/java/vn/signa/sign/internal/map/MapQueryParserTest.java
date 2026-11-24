package vn.signa.sign.internal.map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import vn.signa.shared.error.ApiException;
import vn.signa.shared.error.ErrorCode;
import vn.signa.shared.error.FieldIssue;

class MapQueryParserTest {

  @Test
  void parsesValidQueryWithDefaults() {
    MapQuery q = MapQueryParser.parse("105.80,21.02,105.81,21.03", null, null, null);

    assertThat(q.bbox()).isEqualTo(new BoundingBox(105.80, 21.02, 105.81, 21.03));
    assertThat(q.zoom()).isEqualTo(14);
    assertThat(q.categories()).isEmpty();
    assertThat(q.signTypeCodes()).isEmpty();
  }

  @Test
  void acceptsSpacesSignsAndExponents() {
    MapQuery q = MapQueryParser.parse(" -1e-7 , -90, 180 ,+90", "", " , ", " 0 ");

    assertThat(q.bbox()).isEqualTo(new BoundingBox(-1e-7, -90, 180, 90));
    assertThat(q.zoom()).isZero();
    assertThat(q.categories()).isEmpty();
    assertThat(q.signTypeCodes()).isEmpty();
  }

  @Test
  void filtersAreTrimmedDeduplicatedAndKeepOrder() {
    MapQuery q =
        MapQueryParser.parse(
            "0,0,1,1", " PROHIBITORY,WARNING,,PROHIBITORY ", "P.123a, W.201a", "22");

    assertThat(q.categories()).containsExactly("PROHIBITORY", "WARNING");
    assertThat(q.signTypeCodes()).containsExactly("P.123a", "W.201a");
    assertThat(q.zoom()).isEqualTo(22);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "",
        "1,2,3",
        "1,2,3,4,5",
        "a,0,1,1",
        "0,0,1,",
        "NaN,0,1,1",
        "Infinity,0,1,1",
        "0x1p3,0,1,1",
        "-180.5,0,1,1",
        "0,0,181,1",
        "0,-91,1,1",
        "0,0,1,90.01",
        "1e999,0,1,1",
        "1,0,1,1",
        "0,1,1,1",
        "0,2,1,1"
      })
  void rejectsInvalidBbox(String bbox) {
    assertThat(issues(bbox, null, null, null)).extracting(FieldIssue::field).containsOnly("bbox");
  }

  @Test
  void rejectsMissingBbox() {
    assertThat(issues(null, null, null, null))
        .singleElement()
        .satisfies(i -> assertThat(i.field()).isEqualTo("bbox"));
  }

  @Test
  void bboxAcrossAntimeridianIsRejectedWithItsOwnMessage() {
    assertThat(issues("179,10,-179,11", null, null, null))
        .singleElement()
        .satisfies(i -> assertThat(i.issue()).contains("180°"));
  }

  @ParameterizedTest
  @ValueSource(strings = {"-1", "23", "1.5", "abc", "99999999999"})
  void rejectsInvalidZoom(String zoom) {
    assertThat(issues("0,0,1,1", null, null, zoom))
        .singleElement()
        .satisfies(i -> assertThat(i.field()).isEqualTo("zoom"));
  }

  @Test
  void limitsFilterSize() {
    String fifty = codes(50);
    assertThat(MapQueryParser.parse("0,0,1,1", fifty, fifty, null).categories()).hasSize(50);

    assertThat(issues("0,0,1,1", codes(51), codes(51), null))
        .extracting(FieldIssue::field)
        .containsExactly("categories", "signTypeCodes");
  }

  @Test
  void rejectsCodesLongerThanTheColumn() {
    assertThat(issues("0,0,1,1", "C".repeat(20), "T".repeat(30), null)).isEmpty();
    assertThat(issues("0,0,1,1", "C".repeat(21), "T".repeat(31), null))
        .extracting(FieldIssue::field)
        .containsExactly("categories", "signTypeCodes");
  }

  @Test
  void collectsAllIssuesInOneError() {
    ApiException e =
        catchThrowableOfType(
            ApiException.class, () -> MapQueryParser.parse("x", codes(51), null, "30"));

    assertThat(e.code()).isEqualTo(ErrorCode.VALIDATION_ERROR);
    assertThat(e.details())
        .extracting(FieldIssue::field)
        .containsExactly("bbox", "zoom", "categories");
  }

  private static List<FieldIssue> issues(
      String bbox, String categories, String signTypeCodes, String zoom) {
    try {
      MapQueryParser.parse(bbox, categories, signTypeCodes, zoom);
      return List.of();
    } catch (ApiException e) {
      assertThat(e.code()).isEqualTo(ErrorCode.VALIDATION_ERROR);
      return e.details();
    }
  }

  private static String codes(int n) {
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < n; i++) {
      if (i > 0) sb.append(',');
      sb.append("C").append(i);
    }
    return sb.toString();
  }
}
