package vn.signa.sign.internal.review;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import vn.signa.shared.error.ApiException;
import vn.signa.sign.clustering.GeoPoint;
import vn.signa.sign.internal.review.ReviewRequests.Approve;
import vn.signa.sign.internal.review.ReviewRequests.Edit;
import vn.signa.sign.internal.review.ReviewRequests.Keep;
import vn.signa.sign.internal.review.ReviewRequests.LocationInput;
import vn.signa.sign.internal.review.ReviewRequests.NewSign;
import vn.signa.sign.internal.review.ReviewRequests.Reasoned;

class ReviewRequestsTest {

  private static final Validator VALIDATOR =
      Validation.buildDefaultValidatorFactory().getValidator();
  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final LocationInput HERE = new LocationInput(21.0285, 105.8048);

  @Test
  void approveTrimsCodeAndAcceptsOptionalFields() {
    Approve approve = new Approve(7, "  P.123b ", HERE);

    assertThat(invalidFields(approve)).isEmpty();
    assertThat(approve.signTypeCode()).isEqualTo("P.123b");
    assertThat(approve.change().location()).isEqualTo(new GeoPoint(21.0285, 105.8048));
    assertThat(invalidFields(new Approve(7, null, null))).isEmpty();
  }

  @Test
  void approveRejectsMissingVersionBlankCodeAndBadLocation() {
    assertThat(invalidFields(new Approve(null, null, null))).containsExactly("version");
    assertThat(invalidFields(new Approve(-1, null, null))).containsExactly("version");
    assertThat(invalidFields(new Approve(1, "   ", null))).containsExactly("signTypeCode");
    assertThat(invalidFields(new Approve(1, "x".repeat(31), null))).containsExactly("signTypeCode");
    assertThat(invalidFields(new Approve(1, null, new LocationInput(91.0, 0.0))))
        .containsExactly("location.lat");
    assertThat(invalidFields(new Approve(1, null, new LocationInput(0.0, null))))
        .containsExactly("location.lng");
  }

  @Test
  void reasonIsTrimmedAndBetweenOneAndThousandCharacters() {
    assertThat(new Reasoned(1, "  sai mã  ").reason()).isEqualTo("sai mã");
    assertThat(invalidFields(new Reasoned(1, "x".repeat(1000)))).isEmpty();
    assertThat(invalidFields(new Reasoned(1, "x".repeat(1001)))).containsExactly("reason");
    assertThat(invalidFields(new Reasoned(1, "   "))).containsExactly("reason");
    assertThat(invalidFields(new Reasoned(1, null))).containsExactly("reason");
    assertThat(invalidFields(new Reasoned(null, "ok"))).containsExactly("version");
  }

  @Test
  void keepTreatsNullHeadingAsUnchanged() {
    assertThat(new Keep(1, null, null, null).change().isEmpty()).isTrue();
    SignChange change = new Keep(1, null, null, 0).change();
    assertThat(change.headingSet()).isTrue();
    assertThat(change.headingDeg()).isZero();
    assertThat(invalidFields(new Keep(1, null, null, 360))).containsExactly("headingDeg");
  }

  @Test
  void editDistinguishesOmittedAndNullHeading() {
    SignChange cleared = new Edit(1, null, null, node("null")).change();
    assertThat(cleared.headingSet()).isTrue();
    assertThat(cleared.headingDeg()).isNull();

    SignChange set = new Edit(1, null, null, node("359")).change();
    assertThat(set.headingDeg()).isEqualTo(359);

    SignChange code = new Edit(1, "P.1", null, null).change();
    assertThat(code.headingSet()).isFalse();
    assertThat(code.signTypeCode()).isEqualTo("P.1");
  }

  @Test
  void editNeedsAtLeastOneChangeAndValidHeading() {
    assertThatThrownBy(() -> new Edit(1, null, null, null).change())
        .isInstanceOfSatisfying(
            ApiException.class, e -> assertThat(e.details().getFirst().field()).isEqualTo("body"));
    for (String bad : new String[] {"360", "-1", "1.5", "\"90\"", "99999999999"}) {
      assertThatThrownBy(() -> new Edit(1, null, null, node(bad)).change())
          .as(bad)
          .isInstanceOfSatisfying(
              ApiException.class,
              e -> assertThat(e.details().getFirst().field()).isEqualTo("headingDeg"));
    }
  }

  @Test
  void newSignNeedsCodeAndLocation() {
    assertThat(invalidFields(new NewSign("P.1", HERE, null))).isEmpty();
    assertThat(invalidFields(new NewSign(" ", null, null)))
        .containsExactlyInAnyOrder("signTypeCode", "location");
    assertThat(invalidFields(new NewSign("P.1", HERE, -1))).containsExactly("headingDeg");
  }

  private static JsonNode node(String json) {
    return JSON.readTree(json);
  }

  private static Set<String> invalidFields(Object request) {
    return VALIDATOR.validate(request).stream()
        .map(ConstraintViolation::getPropertyPath)
        .map(Object::toString)
        .collect(Collectors.toSet());
  }
}
