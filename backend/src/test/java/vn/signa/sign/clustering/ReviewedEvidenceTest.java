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
import static vn.signa.sign.clustering.Fixtures.north;
import static vn.signa.sign.clustering.Fixtures.pendingSign;
import static vn.signa.sign.clustering.Fixtures.sub;

import java.math.BigDecimal;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** docs/04 §6 "Sau quyết định của kiểm duyệt viên": β chỉ tính bằng chứng phản đối mới. */
class ReviewedEvidenceTest {

  private final ClusteringEngine engine = engine();

  /** A có W 1,8, kiểm duyệt viên chốt B có W 0,9 lúc {@code T0}. */
  private static SignState approvedAsB() {
    return approvedSign("b", OTHER_CODE, ORIGIN, null)
        .codeWeight(CODE, new CodeWeight(new BigDecimal("1.8000"), 2))
        .codeWeight(OTHER_CODE, new CodeWeight(new BigDecimal("0.9000"), 1))
        .distinctSubmitters(3)
        .denyCount(5)
        .reviewed(T0)
        .build();
  }

  @Test
  @DisplayName("Sau duyệt: W mã khác và R− trước reviewedAt không vào β")
  void preReviewOpposingEvidenceIsSettled() {
    SignState sign = approvedAsB();

    // α = 0,5 + 0,9 + 2 = 3,4; β = 0,5.
    assertThat(Scoring.score(sign, OTHER_CODE)).isEqualByComparingTo("0.8718");
    assertThat(Scoring.opposingWeight(sign, OTHER_CODE)).isZero();
    assertThat(Scoring.denies(sign)).isZero();
    assertThat(sign.codeWeights().get(CODE).weight()).isEqualByComparingTo("1.8");
  }

  @Test
  @DisplayName("Biển chưa duyệt: β tính toàn bộ như công thức cũ")
  void unreviewedSignCountsEverything() {
    SignState sign =
        pendingSign("p", CODE, ORIGIN, null)
            .codeWeight(CODE, new CodeWeight(new BigDecimal("1.8"), 2))
            .codeWeight(OTHER_CODE, new CodeWeight(new BigDecimal("0.9"), 1))
            .build();

    // α = 0,5 + 1,8 = 2,3; β = 0,5 + 0,9 = 1,4.
    assertThat(Scoring.score(sign, CODE)).isEqualByComparingTo("0.6216");
    assertThat(Scoring.opposingWeight(sign, OTHER_CODE)).isEqualByComparingTo("1.8");
  }

  @Test
  @DisplayName("Bằng chứng phản đối sau reviewedAt vẫn vào β")
  void postReviewOpposingEvidenceCounts() {
    SignState sign =
        approvedAsB().toBuilder()
            .addWeightSinceReview(CODE, new BigDecimal("0.5"))
            .addWeightSinceReview(OTHER_CODE, new BigDecimal("0.7"))
            .denySinceReview(1)
            .build();

    // α = 3,4; β = 0,5 + 0,5 + 1 = 2; W của mã chốt sau duyệt không phản đối.
    assertThat(Scoring.opposingWeight(sign, OTHER_CODE)).isEqualByComparingTo("0.5");
    assertThat(Scoring.score(sign, OTHER_CODE)).isEqualByComparingTo("0.6296");
  }

  @Test
  @DisplayName("Xác nhận sau duyệt bằng mã không dẫn đầu không đưa biển về UNDER_REVIEW")
  void confirmationAfterApprovalKeepsApproved() {
    World world = new World(engine).with(approvedAsB());

    ClusteringOutcome outcome = world.submit(sub("s1", "u9").code(OTHER_CODE).at(hours(1)).build());

    SignState sign = outcome.sign();
    assertThat(sign.status()).isEqualTo(SignStatus.APPROVED);
    // α = 0,5 + 1,8 + 2 = 4,3; β = 0,5.
    assertThat(sign.confidence()).isEqualByComparingTo("0.8958");
    assertThat(sign.weightsSinceReview()).containsOnlyKeys(OTHER_CODE);
    assertThat(outcome.events())
        .extracting(SignEvent::type)
        .doesNotContain(SignEventType.MARKED_UNDER_REVIEW);
  }

  @Test
  @DisplayName("Ba đánh giá \"không đúng\" mới từ ba người sau duyệt → UNDER_REVIEW")
  void threeNewDeniesTriggerReview() {
    SignState sign = approvedAsB();
    for (int i = 1; i <= 2; i++) {
      sign = deny(sign, i).sign();
      assertThat(sign.status()).isEqualTo(SignStatus.APPROVED);
    }
    RatingOutcome third = deny(sign, 3);

    assertThat(third.sign().denyCount()).isEqualTo(8);
    assertThat(third.sign().denySinceReview()).isEqualTo(3);
    // α = 3,4; β = 0,5 + 3 = 3,5.
    assertThat(third.sign().confidence()).isEqualByComparingTo("0.4928");
    assertThat(third.sign().status()).isEqualTo(SignStatus.UNDER_REVIEW);
  }

  @Test
  @DisplayName("Đề xuất nhận trước hoặc đúng lúc reviewedAt không vào weightsSinceReview")
  void onlyStrictlyLaterSubmissionsCount() {
    World world = new World(engine).with(approvedAsB());

    ClusteringOutcome outcome =
        world.submit(sub("s1", "u9").code(OTHER_CODE).at(T0).build(), hours(1));

    assertThat(outcome.sign().weightsSinceReview()).isEmpty();
  }

  @Test
  @DisplayName("Đánh giá đúng lúc reviewedAt không tính là sau duyệt")
  void ratingAtReviewTimeIsNotAfter() {
    RatingOutcome outcome =
        engine.applyRating(approvedAsB(), new RatingChange(null, null, RatingValue.DENY, T0));

    assertThat(outcome.sign().denySinceReview()).isZero();
  }

  @Test
  @DisplayName("Tự duyệt đặt mốc rà soát và tính lại điểm theo β mới")
  void autoApprovalSettlesPriorEvidence() {
    World world = new World(engine(ClusteringConfig.DEFAULTS.withAutoApprove(true)));
    for (int i = 1; i <= 3; i++) {
      world.submit(sub("s" + i, "u" + i).p("0.95").at(north(i)).build(), hours(i));
    }
    SignState sign = world.signs.values().iterator().next();

    assertThat(sign.status()).isEqualTo(SignStatus.APPROVED);
    assertThat(sign.reviewedAt()).isEqualTo(hours(3));
    assertThat(sign.weightsSinceReview()).isEmpty();
    assertThat(sign.confidence()).isEqualByComparingTo(Scoring.score(sign, CODE));
  }

  @Test
  @DisplayName("reviewed() xoá bằng chứng sau duyệt cũ; SignState kiểm bất biến")
  void reviewedResetsAndInvariantsHold() {
    SignState later =
        approvedAsB().toBuilder()
            .addWeightSinceReview(CODE, BigDecimal.ONE)
            .denySinceReview(2)
            .reviewed(hours(5))
            .build();
    assertThat(later.weightsSinceReview()).isEmpty();
    assertThat(later.denySinceReview()).isZero();
    assertThat(later.reviewedAt()).isEqualTo(hours(5));

    assertThatThrownBy(() ->
            pendingSign("p", CODE, ORIGIN, null)
                .weightsSinceReview(Map.of(CODE, BigDecimal.ONE))
                .build())
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() ->
            approvedAsB().toBuilder()
                .weightsSinceReview(Map.of(CODE, new BigDecimal("-1")))
                .build())
        .isInstanceOf(IllegalArgumentException.class);
  }

  private RatingOutcome deny(SignState sign, int hour) {
    return engine.applyRating(sign, new RatingChange(null, null, RatingValue.DENY, hours(hour)));
  }
}
