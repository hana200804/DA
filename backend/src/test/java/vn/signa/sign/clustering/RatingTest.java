package vn.signa.sign.clustering;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static vn.signa.sign.clustering.Fixtures.CODE;
import static vn.signa.sign.clustering.Fixtures.ORIGIN;
import static vn.signa.sign.clustering.Fixtures.OTHER_CODE;
import static vn.signa.sign.clustering.Fixtures.T0;
import static vn.signa.sign.clustering.Fixtures.approvedSign;
import static vn.signa.sign.clustering.Fixtures.engine;
import static vn.signa.sign.clustering.Fixtures.hours;
import static vn.signa.sign.clustering.Fixtures.pendingSign;

import java.math.BigDecimal;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class RatingTest {

  private final ClusteringEngine engine = engine();

  private static SignState approvedFromTwo() {
    return approvedSign("a", CODE, ORIGIN, 90)
        .codeWeight(CODE, new CodeWeight(new BigDecimal("1.8"), 2))
        .distinctSubmitters(2)
        .reviewedAt(T0)
        .build();
  }

  private RatingOutcome deny(SignState sign, int hour) {
    return engine.applyRating(sign, new RatingChange(null, null, RatingValue.DENY, hours(hour)));
  }

  @Test
  @DisplayName("docs/04 §6: biển đã duyệt từ 2 người nhận 2 đánh giá \"không đúng\" → 0,63")
  void twoDenies() {
    SignState sign = deny(deny(approvedFromTwo(), 1).sign(), 2).sign();

    // α = 0,5 + 1,8 + 2 = 4,3; β = 0,5 + 2 = 2,5.
    assertThat(sign.confidence()).isEqualByComparingTo("0.6324");
    assertThat(sign.status()).isEqualTo(SignStatus.APPROVED);
    assertThat(sign.denyCount()).isEqualTo(2);
    assertThat(sign.denySinceReview()).isEqualTo(2);
    assertThat(sign.version()).isEqualTo(2);
    assertThat(sign.suggestion()).isNull();
  }

  @Test
  @DisplayName(
      "UT-B08: biển APPROVED nhận 3 đánh giá \"không đúng\" → UNDER_REVIEW, MARKED_UNDER_REVIEW")
  void utB08() {
    SignState sign = approvedFromTwo();
    sign = deny(sign, 1).sign();
    sign = deny(sign, 2).sign();
    RatingOutcome third = deny(sign, 3);

    // Điểm 4,3/7,8 = 0,5513 vẫn ≥ 0,5: rà soát vì đủ 3 đánh giá "không đúng".
    assertThat(third.sign().confidence()).isEqualByComparingTo("0.5513");
    assertThat(third.sign().status()).isEqualTo(SignStatus.UNDER_REVIEW);
    assertThat(third.sign().suggestion()).isEqualTo(Suggestion.INSPECT);
    assertThat(third.events())
        .singleElement()
        .satisfies(e -> {
          assertThat(e.type()).isEqualTo(SignEventType.MARKED_UNDER_REVIEW);
          assertThat(e.actorType()).isEqualTo("SYSTEM");
          assertThat(e.before()).containsEntry("status", "APPROVED");
          assertThat(e.after()).containsEntry("status", "UNDER_REVIEW");
          assertThat(e.reason()).contains("3 đánh giá");
        });
  }

  @Test
  @DisplayName("FR-22: điểm dưới 0,5 → UNDER_REVIEW ngay cả khi chưa đủ 3 đánh giá")
  void lowScoreTriggersReview() {
    SignState sign =
        approvedSign("a", OTHER_CODE, ORIGIN, 90)
            .codeWeight(CODE, new CodeWeight(new BigDecimal("1.8"), 2))
            .codeWeight(OTHER_CODE, new CodeWeight(new BigDecimal("0.5"), 1))
            .distinctSubmitters(3)
            .build();

    RatingOutcome outcome = deny(sign, 1);

    // α = 0,5 + 0,5 + 2 = 3; β = 0,5 + 1,8 + 1 = 3,3.
    assertThat(outcome.sign().confidence()).isEqualByComparingTo("0.4762");
    assertThat(outcome.sign().status()).isEqualTo(SignStatus.UNDER_REVIEW);
    assertThat(outcome.events().getFirst().reason()).contains("0.4762");
  }

  @Test
  @DisplayName("Đánh giá lại \"không đúng\" → \"còn đúng\" trừ R− và deny_since_review")
  void reRate() {
    SignState denied = deny(approvedFromTwo(), 1).sign();

    RatingOutcome outcome =
        engine.applyRating(
            denied, new RatingChange(RatingValue.DENY, hours(1), RatingValue.CONFIRM, hours(2)));

    SignState sign = outcome.sign();
    assertThat(sign.denyCount()).isZero();
    assertThat(sign.denySinceReview()).isZero();
    assertThat(sign.confirmCount()).isEqualTo(1);
    // α = 0,5 + 1,8 + 1 + 2 = 5,3; β = 0,5.
    assertThat(sign.confidence()).isEqualByComparingTo("0.9138");
    assertThat(outcome.events()).isEmpty();

    SignState back =
        engine
            .applyRating(
                sign, new RatingChange(RatingValue.CONFIRM, hours(2), RatingValue.DENY, hours(3)))
            .sign();
    assertThat(back.confirmCount()).isZero();
    assertThat(back.denyCount()).isEqualTo(1);
    assertThat(back.denySinceReview()).isEqualTo(1);
  }

  @Test
  @DisplayName("Đánh giá \"không đúng\" trước lần duyệt không tính vào deny_since_review")
  void denyBeforeReviewNotCounted() {
    SignState sign = approvedFromTwo().toBuilder().denyCount(1).denySinceReview(0).build();

    RatingOutcome outcome =
        engine.applyRating(
            sign,
            new RatingChange(
                RatingValue.DENY, T0.minus(Duration.ofDays(3)), RatingValue.DENY, hours(1)));

    assertThat(outcome.sign().denyCount()).isEqualTo(1);
    assertThat(outcome.sign().denySinceReview()).isEqualTo(1);
  }

  @Test
  @DisplayName("Biển UNDER_REVIEW nhận đánh giá vẫn UNDER_REVIEW, không thêm lịch sử")
  void underReviewStays() {
    SignState sign = approvedFromTwo().toBuilder().status(SignStatus.UNDER_REVIEW).build();

    RatingOutcome outcome = deny(deny(deny(sign, 1).sign(), 2).sign(), 3);

    assertThat(outcome.sign().status()).isEqualTo(SignStatus.UNDER_REVIEW);
    assertThat(outcome.sign().suggestion()).isEqualTo(Suggestion.INSPECT);
    assertThat(outcome.events()).isEmpty();
  }

  @Test
  @DisplayName("Không đánh giá biển chưa công bố")
  void pendingCannotBeRated() {
    SignState pending = pendingSign("p", CODE, ORIGIN, 90).build();

    assertThatThrownBy(() -> deny(pending, 1)).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  @DisplayName("RatingChange: previous và previousAt phải đi cùng nhau")
  void ratingChangeValidation() {
    assertThatThrownBy(() -> new RatingChange(RatingValue.DENY, null, RatingValue.CONFIRM, T0))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
