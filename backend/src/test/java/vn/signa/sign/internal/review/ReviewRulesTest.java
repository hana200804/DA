package vn.signa.sign.internal.review;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.entry;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;
import vn.signa.shared.error.ApiException;
import vn.signa.shared.error.ErrorCode;
import vn.signa.sign.clustering.CodeWeight;
import vn.signa.sign.clustering.GeoPoint;
import vn.signa.sign.clustering.MemberSubmission;
import vn.signa.sign.clustering.MemberUpdate;
import vn.signa.sign.clustering.SignState;
import vn.signa.sign.clustering.SignStatus;
import vn.signa.sign.clustering.SubmissionStatus;
import vn.signa.sign.clustering.Suggestion;
import vn.signa.sign.internal.review.ReviewRules.Event;
import vn.signa.sign.internal.review.ReviewRules.Outcome;
import vn.signa.sign.internal.review.ReviewRules.Target;

class ReviewRulesTest {

  private static final Instant NOW = Instant.parse("2026-11-24T10:00:00Z");
  private static final Instant EARLIER = Instant.parse("2026-11-20T10:00:00Z");
  private static final GeoPoint HERE = new GeoPoint(21.0285, 105.8048);
  private static final GeoPoint THERE = new GeoPoint(21.02851, 105.80473);
  private static final String A = "P.123a";
  private static final String B = "P.123b";
  private static final Target TARGET_B = new Target(B, "PROHIBITORY");

  // ---------- duyệt

  @Test
  void approveWithLeadingCodeAcceptsMembersAndSetsModeratorBoost() {
    SignState sign = pending(member("s1", "u1", A, "0.9000"), member("s2", "u2", A, "0.8800"));

    Outcome outcome = ReviewRules.approve(sign, 3, null, null, NOW);

    SignState approved = outcome.sign();
    assertThat(approved.status()).isEqualTo(SignStatus.APPROVED);
    assertThat(approved.version()).isEqualTo(4);
    assertThat(approved.moderatorBoost()).isEqualByComparingTo("2");
    assertThat(approved.reviewedAt()).isEqualTo(NOW);
    assertThat(approved.denySinceReview()).isZero();
    assertThat(approved.suggestion()).isNull();
    // α = 0,5 + 1,78 + 2 = 4,28; β = 0,5 → 4,28 / 4,78.
    assertThat(approved.confidence()).isEqualByComparingTo("0.8954");
    assertThat(outcome.reviewed()).isTrue();
    assertThat(approved.codeWeights()).isEqualTo(sign.codeWeights());
    assertThat(outcome.members())
        .containsExactly(
            new MemberUpdate("s1", SubmissionStatus.ACCEPTED, null),
            new MemberUpdate("s2", SubmissionStatus.ACCEPTED, null));
    Event event = outcome.events().getFirst();
    assertThat(outcome.events()).hasSize(1);
    assertThat(event.action()).isEqualTo("APPROVED");
    assertThat(event.before())
        .containsOnly(entry("status", "PENDING"), entry("confidence", new BigDecimal("0.7000")));
    assertThat(event.after())
        .containsOnly(entry("status", "APPROVED"), entry("confidence", new BigDecimal("0.8954")));
  }

  @Test
  void approveWithNonLeadingCodeKeepsScoreAboveHalf() {
    SignState sign =
        pending(
            member("s1", "u1", A, "0.9000"),
            member("s2", "u2", A, "0.9000"),
            member("s3", "u3", A, "0.9000"),
            member("s4", "u4", B, "0.9000"));

    Outcome outcome = ReviewRules.approve(sign, 3, TARGET_B, null, NOW);

    SignState approved = outcome.sign();
    assertThat(approved.signTypeCode()).isEqualTo(B);
    // W_A = 2,7 có trước lần duyệt nên không vào β: α = 0,5 + 0,9 + 2 = 3,4; β = 0,5.
    assertThat(approved.confidence()).isEqualByComparingTo("0.8718");
    assertThat(approved.confidence()).isGreaterThanOrEqualTo(new BigDecimal("0.5"));
    assertThat(approved.reviewedAt()).isEqualTo(NOW);
    assertThat(approved.weightsSinceReview()).isEmpty();
    // sign_code_weights giữ nguyên: bằng chứng không mất.
    assertThat(approved.codeWeights()).isEqualTo(sign.codeWeights());
    assertThat(approved.codeWeights().get(A).weight()).isEqualByComparingTo("2.7");
    assertThat(outcome.members())
        .containsExactlyInAnyOrder(
            new MemberUpdate("s1", SubmissionStatus.REJECTED, ReviewRules.REASON_OTHER_CODE),
            new MemberUpdate("s2", SubmissionStatus.REJECTED, ReviewRules.REASON_OTHER_CODE),
            new MemberUpdate("s3", SubmissionStatus.REJECTED, ReviewRules.REASON_OTHER_CODE),
            new MemberUpdate("s4", SubmissionStatus.ACCEPTED, null));
    assertThat(outcome.events().getFirst().before()).containsEntry("signTypeCode", A);
    assertThat(outcome.events().getFirst().after()).containsEntry("signTypeCode", B);
  }

  @Test
  void approveWithCodeOfOtherCategoryUpdatesCategory() {
    SignState sign = pending(member("s1", "u1", A, "0.9000"));

    Outcome outcome = ReviewRules.approve(sign, 3, new Target("W.201", "WARNING"), null, NOW);

    assertThat(outcome.sign().categoryCode()).isEqualTo("WARNING");
    // Mã chưa có đề xuất: α = 0,5 + 2, β = 0,5.
    assertThat(outcome.sign().confidence()).isEqualByComparingTo("0.8333");
    assertThat(outcome.events().getFirst().after())
        .containsEntry("signTypeCode", "W.201")
        .containsEntry("categoryCode", "WARNING");
  }

  @Test
  void approveWithLocationLocksIt() {
    SignState sign = pending(member("s1", "u1", A, "0.9000"));

    Outcome outcome = ReviewRules.approve(sign, 3, null, THERE, NOW);

    assertThat(outcome.sign().location()).isEqualTo(THERE);
    assertThat(outcome.sign().locationLocked()).isTrue();
    assertThat(outcome.events().getFirst().before())
        .containsEntry("location", Map.of("lat", HERE.lat(), "lng", HERE.lng()))
        .containsEntry("locationLocked", false);
    assertThat(outcome.events().getFirst().after())
        .containsEntry("location", Map.of("lat", THERE.lat(), "lng", THERE.lng()))
        .containsEntry("locationLocked", true);
  }

  @Test
  void keepWithFiveOldDeniesScoresAtLeastHalf() {
    SignState sign =
        underReview(member("s1", "u1", A, "0.9000", SubmissionStatus.ACCEPTED)).toBuilder()
            .codeWeight(B, new CodeWeight(new BigDecimal("1.5000"), 2))
            .weightsSinceReview(Map.of(B, new BigDecimal("1.5000")))
            .denyCount(5)
            .denySinceReview(5)
            .build();
    // Trước khi giữ: α = 0,5 + 0,9 + 2 = 3,4; β = 0,5 + 1,5 + 5 = 7.
    assertThat(vn.signa.sign.clustering.Scoring.score(sign, A)).isEqualByComparingTo("0.3269");

    Outcome outcome = ReviewRules.keep(sign, 3, SignChange.NONE, null, NOW);

    SignState kept = outcome.sign();
    // Bằng chứng phản đối trước lúc giữ coi như đã xem xét: β = 0,5.
    assertThat(kept.confidence()).isEqualByComparingTo("0.8718");
    assertThat(kept.denyCount()).isEqualTo(5);
    assertThat(kept.denySinceReview()).isZero();
    assertThat(kept.weightsSinceReview()).isEmpty();
    assertThat(kept.codeWeights()).isEqualTo(sign.codeWeights());
  }

  @Test
  void staleVersionOrWrongStatusIsAlreadyProcessed() {
    SignState sign = pending(member("s1", "u1", A, "0.9000"));
    SignState approved = sign.toBuilder().status(SignStatus.APPROVED).build();

    assertConflict(() -> ReviewRules.approve(sign, 2, null, null, NOW));
    assertConflict(() -> ReviewRules.approve(approved, 3, null, null, NOW));
    assertConflict(() -> ReviewRules.reject(approved, 3, "sai", NOW));
    assertConflict(() -> ReviewRules.keep(sign, 3, SignChange.NONE, null, NOW));
    assertConflict(() -> ReviewRules.remove(sign, 3, "sai", NOW));
    assertConflict(() ->
        ReviewRules.edit(
            sign.toBuilder().status(SignStatus.REMOVED).build(),
            3,
            new SignChange(null, THERE, false, null),
            null));
    assertThatThrownBy(() -> ReviewRules.approve(approved, 3, null, null, NOW))
        .hasMessageContaining("APPROVED");
  }

  // ---------- từ chối

  @Test
  void rejectRejectsEveryMemberWithModeratorReason() {
    SignState sign =
        pending(
            member("s1", "u1", A, "0.9000"),
            member("s2", "u2", B, "0.9000", SubmissionStatus.REJECTED));

    Outcome outcome = ReviewRules.reject(sign, 3, "Không có biển ở đây", NOW);

    assertThat(outcome.sign().status()).isEqualTo(SignStatus.REJECTED);
    assertThat(outcome.sign().reviewedAt()).isEqualTo(NOW);
    assertThat(outcome.sign().suggestion()).isNull();
    assertThat(outcome.sign().version()).isEqualTo(4);
    assertThat(outcome.members())
        .containsExactly(new MemberUpdate("s1", SubmissionStatus.REJECTED, "Không có biển ở đây"));
    Event event = outcome.events().getFirst();
    assertThat(event.action()).isEqualTo("REJECTED");
    assertThat(event.reason()).isEqualTo("Không có biển ở đây");
    assertThat(event.before()).containsOnly(entry("status", "PENDING"));
    assertThat(event.after()).containsOnly(entry("status", "REJECTED"));
  }

  @Test
  void memberReasonIsCutToColumnLength() {
    String reason = "x".repeat(999) + "y";
    Outcome outcome = ReviewRules.reject(pending(member("s1", "u1", A, "0.9000")), 3, reason, NOW);

    assertThat(outcome.members().getFirst().rejectReason()).hasSize(500);
    assertThat(outcome.events().getFirst().reason()).isEqualTo(reason);
    assertThat(ReviewRules.truncate("ab😀c", 3)).isEqualTo("ab");
    assertThat(ReviewRules.truncate("abc", 3)).isEqualTo("abc");
  }

  // ---------- giữ

  @Test
  void keepWithoutChangesRecordsOnlyKept() {
    SignState sign =
        underReview(
            member("s1", "u1", A, "0.9000", SubmissionStatus.ACCEPTED),
            member("s2", "u2", A, "0.9000", SubmissionStatus.GROUPED));

    Outcome outcome = ReviewRules.keep(sign, 3, SignChange.NONE, null, NOW);

    SignState kept = outcome.sign();
    assertThat(kept.status()).isEqualTo(SignStatus.APPROVED);
    assertThat(kept.moderatorBoost()).isEqualByComparingTo("2");
    assertThat(kept.denySinceReview()).isZero();
    assertThat(kept.reviewedAt()).isEqualTo(NOW);
    assertThat(kept.suggestion()).isNull();
    assertThat(kept.version()).isEqualTo(4);
    assertThat(outcome.members())
        .containsExactly(new MemberUpdate("s2", SubmissionStatus.ACCEPTED, null));
    assertThat(outcome.events()).extracting(Event::action).containsExactly("KEPT");
    assertThat(outcome.events().getFirst().before()).containsEntry("status", "UNDER_REVIEW");
    assertThat(outcome.events().getFirst().after()).containsEntry("status", "APPROVED");
  }

  @Test
  void keepWithChangesRecordsEditedThenKept() {
    SignState sign = underReview(member("s1", "u1", A, "0.9000", SubmissionStatus.ACCEPTED));

    Outcome outcome = ReviewRules.keep(sign, 3, new SignChange(null, null, true, 180), null, NOW);

    assertThat(outcome.sign().headingDeg()).isEqualTo(180);
    assertThat(outcome.events()).extracting(Event::action).containsExactly("EDITED", "KEPT");
    assertThat(outcome.events().get(0).before()).containsOnly(entry("headingDeg", 90));
    assertThat(outcome.events().get(0).after()).containsOnly(entry("headingDeg", 180));
    assertThat(outcome.events().get(1).after()).doesNotContainKey("headingDeg");
  }

  // ---------- gỡ

  @Test
  void removeRejectsGroupedConfirmationsOnly() {
    SignState sign =
        underReview(
            member("s1", "u1", A, "0.9000", SubmissionStatus.ACCEPTED),
            member("s2", "u2", A, "0.9000", SubmissionStatus.GROUPED));

    Outcome outcome = ReviewRules.remove(sign, 3, "Biển đã bị tháo", NOW);

    assertThat(outcome.sign().status()).isEqualTo(SignStatus.REMOVED);
    assertThat(outcome.sign().version()).isEqualTo(4);
    assertThat(outcome.members())
        .containsExactly(
            new MemberUpdate("s2", SubmissionStatus.REJECTED, ReviewRules.REASON_REMOVED));
    assertThat(outcome.events().getFirst().action()).isEqualTo("REMOVED");
    assertThat(outcome.events().getFirst().reason()).isEqualTo("Biển đã bị tháo");
    assertThat(outcome.events().getFirst().after()).containsOnly(entry("status", "REMOVED"));
  }

  @Test
  void removeAcceptsApprovedSign() {
    SignState sign = approved(member("s1", "u1", A, "0.9000", SubmissionStatus.ACCEPTED));

    Outcome outcome = ReviewRules.remove(sign, 3, "Sai", NOW);

    assertThat(outcome.sign().status()).isEqualTo(SignStatus.REMOVED);
    assertThat(outcome.members()).isEmpty();
  }

  // ---------- sửa

  @Test
  void editPendingLocationLocksWithoutTouchingMembers() {
    SignState sign = pending(member("s1", "u1", A, "0.9000"));

    Outcome outcome = ReviewRules.edit(sign, 3, new SignChange(null, THERE, false, null), null);

    assertThat(outcome.sign().status()).isEqualTo(SignStatus.PENDING);
    assertThat(outcome.sign().location()).isEqualTo(THERE);
    assertThat(outcome.sign().locationLocked()).isTrue();
    assertThat(outcome.sign().version()).isEqualTo(4);
    assertThat(outcome.sign().confidence()).isEqualByComparingTo(sign.confidence());
    assertThat(outcome.reviewed()).isFalse();
    assertThat(outcome.members()).isEmpty();
    assertThat(outcome.events()).extracting(Event::action).containsExactly("EDITED");
  }

  @Test
  void editPendingCodeIsBusinessRule() {
    SignState sign = pending(member("s1", "u1", A, "0.9000"));

    assertThatThrownBy(
            () -> ReviewRules.edit(sign, 3, new SignChange(B, null, false, null), TARGET_B))
        .isInstanceOfSatisfying(
            ApiException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.BUSINESS_RULE));
  }

  @Test
  void editWithoutEffectiveChangeIsBusinessRule() {
    SignState sign = approved(member("s1", "u1", A, "0.9000", SubmissionStatus.ACCEPTED));

    assertThatThrownBy(() ->
            ReviewRules.edit(
                sign, 3, new SignChange(A, null, true, 90), new Target(A, "PROHIBITORY")))
        .isInstanceOfSatisfying(
            ApiException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.BUSINESS_RULE));
  }

  @Test
  void editApprovedCodeSettlesMembersAndWeights() {
    SignState sign =
        approved(
            member("s1", "u1", A, "0.9000", SubmissionStatus.ACCEPTED),
            member("s2", "u2", B, "0.8000", SubmissionStatus.REJECTED));

    Outcome outcome = ReviewRules.edit(sign, 3, new SignChange(B, null, false, null), TARGET_B);

    assertThat(outcome.sign().signTypeCode()).isEqualTo(B);
    assertThat(outcome.sign().status()).isEqualTo(SignStatus.APPROVED);
    assertThat(outcome.sign().reviewedAt()).isEqualTo(EARLIER);
    assertThat(outcome.members())
        .containsExactlyInAnyOrder(
            new MemberUpdate("s1", SubmissionStatus.REJECTED, ReviewRules.REASON_OTHER_CODE),
            new MemberUpdate("s2", SubmissionStatus.ACCEPTED, null));
    assertThat(outcome.sign().codeWeights()).isEqualTo(sign.codeWeights());
    // α = 0,5 + 0,8 + 2 = 3,3; β = 0,5.
    assertThat(outcome.sign().confidence()).isEqualByComparingTo("0.8684");
    assertThat(outcome.events().getFirst().after())
        .containsEntry("signTypeCode", B)
        .containsEntry("confidence", new BigDecimal("0.8684"));
  }

  @Test
  void editUnderReviewCodeLeavesSameCodeMembersForKeep() {
    SignState sign =
        underReview(
            member("s1", "u1", A, "0.9000", SubmissionStatus.ACCEPTED),
            member("s2", "u2", B, "0.8000", SubmissionStatus.GROUPED));

    Outcome outcome = ReviewRules.edit(sign, 3, new SignChange(B, null, false, null), TARGET_B);

    assertThat(outcome.sign().status()).isEqualTo(SignStatus.UNDER_REVIEW);
    assertThat(outcome.members())
        .containsExactly(
            new MemberUpdate("s1", SubmissionStatus.REJECTED, ReviewRules.REASON_OTHER_CODE));
  }

  @Test
  void editCanClearHeading() {
    SignState sign = approved(member("s1", "u1", A, "0.9000", SubmissionStatus.ACCEPTED));

    Outcome outcome = ReviewRules.edit(sign, 3, new SignChange(null, null, true, null), null);

    assertThat(outcome.sign().headingDeg()).isNull();
    assertThat(outcome.events().getFirst().before()).containsOnly(entry("headingDeg", 90));
    assertThat(outcome.events().getFirst().after()).containsOnly(entry("headingDeg", null));
  }

  // ---------- thêm

  @Test
  void createIsApprovedLockedWithModeratorScore() {
    Outcome outcome = ReviewRules.create("new-1", TARGET_B, HERE, null, NOW);

    SignState sign = outcome.sign();
    assertThat(sign.status()).isEqualTo(SignStatus.APPROVED);
    assertThat(sign.version()).isZero();
    assertThat(sign.locationLocked()).isTrue();
    assertThat(sign.moderatorBoost()).isEqualByComparingTo("2");
    assertThat(sign.reviewedAt()).isEqualTo(NOW);
    assertThat(sign.confidence()).isEqualByComparingTo("0.8333");
    Event event = outcome.events().getFirst();
    assertThat(event.action()).isEqualTo("CREATED");
    assertThat(event.before()).isNull();
    assertThat(event.after())
        .containsEntry("status", "APPROVED")
        .containsEntry("signTypeCode", B)
        .containsEntry("headingDeg", null);
  }

  @Test
  void confidenceComparedByValueNotScale() {
    SignState sign = pending(member("s1", "u1", A, "0.9000"));
    SignState rescaled = sign.toBuilder().confidence(new BigDecimal("0.70")).build();

    assertThat(ReviewRules.changed(sign, rescaled)).isFalse();
  }

  // ---------- dữ liệu mẫu

  private static MemberSubmission member(String id, String user, String code, String weight) {
    return member(id, user, code, weight, SubmissionStatus.GROUPED);
  }

  private static MemberSubmission member(
      String id, String user, String code, String weight, SubmissionStatus status) {
    return new MemberSubmission(
        id, user, code, new BigDecimal(weight), HERE, EARLIER.plusSeconds(id.hashCode()), status);
  }

  private static SignState pending(MemberSubmission... members) {
    return base(SignStatus.PENDING, members)
        .suggestion(Suggestion.WAIT)
        .confidence(new BigDecimal("0.7000"))
        .build();
  }

  private static SignState underReview(MemberSubmission... members) {
    return base(SignStatus.UNDER_REVIEW, members)
        .suggestion(Suggestion.INSPECT)
        .moderatorBoost(new BigDecimal("2.00"))
        .denyCount(3)
        .denySinceReview(3)
        .reviewedAt(EARLIER)
        .confidence(new BigDecimal("0.4500"))
        .build();
  }

  private static SignState approved(MemberSubmission... members) {
    return base(SignStatus.APPROVED, members)
        .moderatorBoost(new BigDecimal("2.00"))
        .reviewedAt(EARLIER)
        .confidence(new BigDecimal("0.8000"))
        .build();
  }

  private static SignState.Builder base(SignStatus status, MemberSubmission... members) {
    SignState.Builder b =
        SignState.builder()
            .id("sign-1")
            .status(status)
            .signTypeCode(A)
            .categoryCode("PROHIBITORY")
            .location(HERE)
            .headingDeg(90)
            .version(3)
            .members(List.of(members));
    Map<String, BigDecimal> totals = new TreeMap<>();
    Map<String, Set<String>> users = new TreeMap<>();
    for (MemberSubmission m : members) {
      totals.merge(m.signTypeCode(), m.weight(), BigDecimal::add);
      users.computeIfAbsent(m.signTypeCode(), c -> new TreeSet<>()).add(m.userId());
    }
    totals.forEach((code, w) -> b.codeWeight(code, new CodeWeight(w, users.get(code).size())));
    return b.distinctSubmitters(
        (int) List.of(members).stream().map(MemberSubmission::userId).distinct().count());
  }

  private static void assertConflict(ThrowingCallable call) {
    assertThatThrownBy(call)
        .isInstanceOfSatisfying(
            ApiException.class,
            e -> assertThat(e.code()).isEqualTo(ErrorCode.SIGN_ALREADY_PROCESSED));
  }
}
