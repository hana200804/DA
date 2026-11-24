package vn.signa.sign.internal.review;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import vn.signa.sign.clustering.ClusteringConfig;
import vn.signa.sign.clustering.ClusteringEngine;
import vn.signa.sign.clustering.ClusteringOutcome;
import vn.signa.sign.clustering.CodeWeight;
import vn.signa.sign.clustering.GeoPoint;
import vn.signa.sign.clustering.MemberSubmission;
import vn.signa.sign.clustering.RatingChange;
import vn.signa.sign.clustering.RatingValue;
import vn.signa.sign.clustering.SignState;
import vn.signa.sign.clustering.SignStatus;
import vn.signa.sign.clustering.SubmissionFlag;
import vn.signa.sign.clustering.SubmissionInput;
import vn.signa.sign.clustering.SubmissionStatus;
import vn.signa.sign.clustering.Suggestion;
import vn.signa.sign.internal.review.ReviewRules.Outcome;
import vn.signa.sign.internal.review.ReviewRules.Target;

/**
 * docs/04 §6 "Sau quyết định của kiểm duyệt viên", đi qua lát 6 và lõi gom cụm: duyệt bằng mã không
 * dẫn đầu, xác nhận, ba đánh giá "không đúng" mới, rồi giữ biển. Không cần Docker.
 */
class ReviewEvidenceChainTest {

  private static final Instant T0 = Instant.parse("2026-11-20T08:00:00Z");
  private static final GeoPoint HERE = new GeoPoint(21.0285, 105.8048);
  private static final String A = "P.123a";
  private static final String B = "P.123b";
  private static final String CATEGORY = "PROHIBITORY";

  private final ClusteringEngine engine =
      new ClusteringEngine(ClusteringConfig.DEFAULTS, () -> "unused");

  @Test
  void decisionSettlesPriorEvidenceButNewEvidenceStillCounts() {
    // A có W 1,8 từ hai người, B có W 0,9 từ một người.
    SignState pending =
        SignState.builder()
            .id("sign-1")
            .status(SignStatus.PENDING)
            .signTypeCode(A)
            .categoryCode(CATEGORY)
            .location(HERE)
            .codeWeight(A, new CodeWeight(new BigDecimal("1.8000"), 2))
            .codeWeight(B, new CodeWeight(new BigDecimal("0.9000"), 1))
            .distinctSubmitters(3)
            .members(
                List.of(
                    member("s1", "u1", A, T0),
                    member("s2", "u2", A, T0.plusSeconds(1)),
                    member("s3", "u3", B, T0.plusSeconds(2))))
            .confidence(new BigDecimal("0.6216"))
            .suggestion(Suggestion.INSPECT)
            .version(3)
            .build();
    Map<String, CodeWeight> weights = pending.codeWeights();

    // 1. Duyệt bằng B: điểm ≥ 0,5, sign_code_weights không đổi.
    Instant reviewedAt = T0.plus(Duration.ofHours(1));
    Outcome approved = ReviewRules.approve(pending, 3, new Target(B, CATEGORY), null, reviewedAt);
    SignState sign = approved.sign();
    assertThat(sign.status()).isEqualTo(SignStatus.APPROVED);
    // α = 0,5 + 0,9 + 2 = 3,4; β = 0,5.
    assertThat(sign.confidence()).isEqualByComparingTo("0.8718");
    assertThat(sign.codeWeights()).isEqualTo(weights);

    // 2. Một CONFIRM (đề xuất B mới): biển không bị rà soát lại.
    ClusteringOutcome confirmed =
        engine.process(
            new SubmissionInput(
                "s4",
                "u4",
                B,
                CATEGORY,
                new BigDecimal("0.9"),
                HERE,
                null,
                EnumSet.noneOf(SubmissionFlag.class),
                reviewedAt.plus(Duration.ofHours(1))),
            List.of(sign),
            reviewedAt.plus(Duration.ofHours(1)));
    assertThat(confirmed.submissionStatus()).isEqualTo(SubmissionStatus.ACCEPTED);
    sign = confirmed.sign();
    assertThat(sign.status()).isEqualTo(SignStatus.APPROVED);
    // α = 0,5 + 1,8 + 2 = 4,3; β = 0,5.
    assertThat(sign.confidence()).isEqualByComparingTo("0.8958");
    assertThat(sign.codeWeights().get(A).weight()).isEqualByComparingTo("1.8");

    // 3. Ba DENY mới từ ba người: chuyển UNDER_REVIEW.
    for (int i = 1; i <= 3; i++) {
      sign =
          engine
              .applyRating(
                  sign,
                  new RatingChange(
                      null, null, RatingValue.DENY, reviewedAt.plus(Duration.ofHours(1 + i))))
              .sign();
    }
    assertThat(sign.denySinceReview()).isEqualTo(3);
    assertThat(sign.status()).isEqualTo(SignStatus.UNDER_REVIEW);

    // 4. Giữ biển: ba DENY cũ coi như đã xem xét, điểm ≥ 0,5.
    Outcome kept =
        ReviewRules.keep(
            sign, sign.version(), SignChange.NONE, null, reviewedAt.plus(Duration.ofDays(1)));
    assertThat(kept.sign().status()).isEqualTo(SignStatus.APPROVED);
    assertThat(kept.sign().denyCount()).isEqualTo(3);
    assertThat(kept.sign().confidence()).isEqualByComparingTo("0.8958");
    assertThat(kept.sign().codeWeights().get(A).weight()).isEqualByComparingTo("1.8");
  }

  private static MemberSubmission member(String id, String user, String code, Instant at) {
    return new MemberSubmission(
        id, user, code, new BigDecimal("0.9000"), HERE, at, SubmissionStatus.GROUPED);
  }
}
