package vn.signa.sign.clustering;

import static org.assertj.core.api.Assertions.assertThat;
import static vn.signa.sign.clustering.Fixtures.CODE;
import static vn.signa.sign.clustering.Fixtures.ORIGIN;
import static vn.signa.sign.clustering.Fixtures.OTHER_CODE;
import static vn.signa.sign.clustering.Fixtures.T0;
import static vn.signa.sign.clustering.Fixtures.approvedSign;
import static vn.signa.sign.clustering.Fixtures.engine;
import static vn.signa.sign.clustering.Fixtures.hours;
import static vn.signa.sign.clustering.Fixtures.member;
import static vn.signa.sign.clustering.Fixtures.north;
import static vn.signa.sign.clustering.Fixtures.pendingSign;
import static vn.signa.sign.clustering.Fixtures.rejectedSign;
import static vn.signa.sign.clustering.Fixtures.sub;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ClusteringEngineTest {

  private final World world = new World(engine());

  private static SignState approvedFromTwo() {
    return approvedSign("approved", CODE, ORIGIN, 90)
        .codeWeight(CODE, new CodeWeight(new BigDecimal("1.8"), 2))
        .distinctSubmitters(2)
        .members(
            List.of(
                member("m1", "u1", CODE, "0.9", ORIGIN, T0.minus(Duration.ofDays(2))),
                member("m2", "u2", CODE, "0.9", north(2), T0.minus(Duration.ofDays(2)))))
        .confidence(new BigDecimal("0.8846"))
        .build();
  }

  @Test
  @DisplayName(
      "UT-B01: 2 người, cùng mã, cách 8 m, cùng hướng, p = 0,9 → cùng biển, 0,82, Nên duyệt")
  void utB01() {
    ClusteringOutcome first = world.submit(sub("s1", "u1").build());
    ClusteringOutcome second = world.submit(sub("s2", "u2").at(north(8)).at(hours(1)).build());

    assertThat(first.signCreated()).isTrue();
    assertThat(second.signCreated()).isFalse();
    assertThat(second.sign().id()).isEqualTo(first.sign().id());
    assertThat(world.signs).hasSize(1);
    SignState sign = second.sign();
    assertThat(sign.status()).isEqualTo(SignStatus.PENDING);
    assertThat(sign.confidence()).isEqualByComparingTo("0.8214");
    assertThat(sign.confidence().setScale(2, java.math.RoundingMode.HALF_UP))
        .isEqualByComparingTo("0.82");
    assertThat(sign.suggestion()).isEqualTo(Suggestion.APPROVE);
    assertThat(sign.distinctSubmitters()).isEqualTo(2);
    assertThat(sign.codeWeights().get(CODE)).isEqualTo(new CodeWeight(new BigDecimal("1.8000"), 2));
    assertThat(second.submissionStatus()).isEqualTo(SubmissionStatus.GROUPED);
    assertThat(second.submissionWeight()).isEqualByComparingTo("0.9");
    assertThat(sign.version()).isEqualTo(1);
    // Hai trọng số bằng nhau: trung vị là trung điểm.
    assertThat(sign.location().lat()).isCloseTo((ORIGIN.lat() + north(8).lat()) / 2, within());
    assertThat(sign.location().lng()).isCloseTo(ORIGIN.lng(), within());
  }

  @Test
  @DisplayName("docs/04 §6: một người p = 0,9 → 0,74, Chờ thêm")
  void onePerson() {
    ClusteringOutcome outcome = world.submit(sub("s1", "u1").build());

    assertThat(outcome.sign().confidence()).isEqualByComparingTo("0.7368");
    assertThat(outcome.sign().suggestion()).isEqualTo(Suggestion.WAIT);
    assertThat(outcome.sign().version()).isZero();
    assertThat(outcome.events())
        .singleElement()
        .satisfies(e -> {
          assertThat(e.type()).isEqualTo(SignEventType.CREATED);
          assertThat(e.before()).isNull();
          assertThat(e.after())
              .containsEntry("status", "PENDING")
              .containsEntry("signTypeCode", CODE)
              .containsEntry("headingDeg", 90)
              .containsEntry("flags", List.of());
          assertThat(e.actorType()).isEqualTo("SYSTEM");
        });
  }

  @Test
  @DisplayName(
      "UT-B02: 1 người gửi 5 lần trong 1 giờ → distinct_submitters = 1, chỉ lần đầu có trọng số")
  void utB02() {
    for (int i = 0; i < 5; i++) {
      world.submit(sub("s" + i, "u1").at(north(i)).at(hours(i * 0.25)).build());
    }

    assertThat(world.signs).hasSize(1);
    SignState sign = world.signs.values().iterator().next();
    assertThat(sign.distinctSubmitters()).isEqualTo(1);
    assertThat(world.outcomes)
        .extracting(o -> o.submissionWeight().toPlainString())
        .containsExactly("0.9000", "0.0000", "0.0000", "0.0000", "0.0000");
    assertThat(sign.codeWeights().get(CODE)).isEqualTo(new CodeWeight(new BigDecimal("0.9000"), 1));
    assertThat(sign.confidence()).isEqualByComparingTo("0.7368");
    // Đề xuất không trọng số không kéo vị trí.
    assertThat(sign.location()).isEqualTo(ORIGIN);
  }

  @Test
  @DisplayName("Đề xuất lặp sau đúng 12 giờ thì có trọng số lại")
  void repeatAfterTwelveHours() {
    world.submit(sub("s1", "u1").build());
    ClusteringOutcome justBefore =
        world.submit(sub("s2", "u1").at(T0.plus(Duration.ofHours(12)).minusMillis(1)).build());
    ClusteringOutcome after =
        world.submit(sub("s3", "u1").at(T0.plus(Duration.ofHours(12))).build());

    assertThat(justBefore.submissionWeight()).isEqualByComparingTo("0");
    assertThat(after.submissionWeight()).isEqualByComparingTo("0.9");
    assertThat(after.sign().distinctSubmitters()).isEqualTo(1);
    assertThat(after.sign().codeWeights().get(CODE).weight()).isEqualByComparingTo("1.8");
    assertThat(after.sign().codeWeights().get(CODE).submitterCount()).isEqualTo(1);
  }

  @Test
  @DisplayName("UT-B03: cùng mã, cách 5 m, hướng ngược → hai biển")
  void utB03() {
    ClusteringOutcome a = world.submit(sub("s1", "u1").heading(0).build());
    ClusteringOutcome b = world.submit(sub("s2", "u2").heading(180).at(north(5)).build());

    assertThat(b.signCreated()).isTrue();
    assertThat(b.sign().id()).isNotEqualTo(a.sign().id());
    assertThat(world.signs).hasSize(2);
  }

  @Test
  @DisplayName("UT-B04: đề xuất có hướng, biển PENDING không hướng, cách 10 m → gán vào biển đó")
  void utB04() {
    world.with(pendingSign("p", CODE, north(10), null).build());

    ClusteringOutcome outcome = world.submit(sub("s1", "u1").heading(90).build());

    assertThat(outcome.signCreated()).isFalse();
    assertThat(outcome.sign().id()).isEqualTo("p");
    assertThat(outcome.sign().headingDeg()).isNull();
  }

  @Test
  @DisplayName("UT-B04 ngược lại: đề xuất không hướng, biển có hướng → bỏ qua điều kiện hướng")
  void utB04Reverse() {
    world.with(pendingSign("p", CODE, north(10), 270).build());

    ClusteringOutcome outcome = world.submit(sub("s1", "u1").heading(null).build());

    assertThat(outcome.sign().id()).isEqualTo("p");
    assertThat(outcome.sign().headingDeg()).isEqualTo(270);
  }

  @Test
  @DisplayName("UT-B05: mã khác cạnh biển APPROVED → biển PENDING mới, DIFFERS_FROM_APPROVED")
  void utB05() {
    world.with(approvedFromTwo());

    ClusteringOutcome outcome = world.submit(sub("s1", "u3").code(OTHER_CODE).at(north(5)).build());

    assertThat(outcome.signCreated()).isTrue();
    SignState sign = outcome.sign();
    assertThat(sign.status()).isEqualTo(SignStatus.PENDING);
    assertThat(sign.signTypeCode()).isEqualTo(OTHER_CODE);
    assertThat(sign.flags()).containsExactly(SignFlag.DIFFERS_FROM_APPROVED);
    assertThat(sign.relatedSignId()).isEqualTo("approved");
    assertThat(sign.suggestion()).isEqualTo(Suggestion.INSPECT);
    assertThat(sign.location()).isEqualTo(north(5));
    assertThat(outcome.events().getFirst().after())
        .containsEntry("flags", List.of("DIFFERS_FROM_APPROVED"))
        .containsEntry("relatedSignId", "approved");
    assertThat(world.sign("approved")).isEqualTo(approvedFromTwo());
  }

  @Test
  @DisplayName("UT-B05: biển UNDER_REVIEW khác mã gần nhất được liên kết")
  void differsLinksNearestPublished() {
    world.with(approvedSign("far", CODE, north(15), 90).build());
    world.with(approvedSign("near", CODE, north(3), 90).status(SignStatus.UNDER_REVIEW).build());

    ClusteringOutcome outcome = world.submit(sub("s1", "u3").code(OTHER_CODE).build());

    assertThat(outcome.sign().relatedSignId()).isEqualTo("near");
  }

  @Test
  @DisplayName("UT-B06: cùng mã cạnh biển APPROVED → xác nhận, không tạo biển mới")
  void utB06() {
    world.with(approvedFromTwo());

    ClusteringOutcome outcome = world.submit(sub("s1", "u3").at(north(6)).build());

    assertThat(outcome.signCreated()).isFalse();
    assertThat(world.signs).hasSize(1);
    SignState sign = outcome.sign();
    assertThat(sign.id()).isEqualTo("approved");
    assertThat(sign.status()).isEqualTo(SignStatus.APPROVED);
    assertThat(sign.signTypeCode()).isEqualTo(CODE);
    assertThat(sign.suggestion()).isNull();
    assertThat(outcome.submissionStatus()).isEqualTo(SubmissionStatus.ACCEPTED);
    assertThat(sign.distinctSubmitters()).isEqualTo(3);
    // α = 0,5 + 2,7 + 2 = 5,2; β = 0,5.
    assertThat(sign.confidence()).isEqualByComparingTo("0.9123");
    assertThat(sign.version()).isEqualTo(1);
    assertThat(sign.members()).extracting(MemberSubmission::id).containsExactly("m1", "m2", "s1");
    assertThat(sign.location()).isEqualTo(north(2));
    assertThat(outcome.events()).isEmpty();
  }

  @Test
  @DisplayName("Xác nhận biển UNDER_REVIEW: đề xuất GROUPED, gợi ý Cần xem kỹ")
  void confirmUnderReview() {
    world.with(approvedFromTwo().toBuilder().status(SignStatus.UNDER_REVIEW).build());

    ClusteringOutcome outcome = world.submit(sub("s1", "u3").build());

    assertThat(outcome.sign().status()).isEqualTo(SignStatus.UNDER_REVIEW);
    assertThat(outcome.submissionStatus()).isEqualTo(SubmissionStatus.GROUPED);
    assertThat(outcome.sign().suggestion()).isEqualTo(Suggestion.INSPECT);
  }

  @Test
  @DisplayName("Xác nhận biển đã duyệt có vị trí chốt thì không đổi vị trí")
  void lockedLocationStays() {
    world.with(approvedFromTwo().toBuilder().locationLocked(true).location(north(1)).build());

    ClusteringOutcome outcome = world.submit(sub("s1", "u3").at(north(15)).build());

    assertThat(outcome.sign().location()).isEqualTo(north(1));
  }

  @Test
  @DisplayName("Biển đã duyệt có điểm dưới 0,5 sau xác nhận thì rà soát lại")
  void confirmationCanTriggerReview() {
    SignState weak =
        approvedSign("approved", CODE, ORIGIN, 90)
            .moderatorBoost(BigDecimal.ZERO)
            .codeWeight(OTHER_CODE, new CodeWeight(new BigDecimal("5"), 5))
            .distinctSubmitters(5)
            .build();
    world.with(weak);

    ClusteringOutcome outcome = world.submit(sub("s1", "u9").build());

    assertThat(outcome.sign().confidence()).isEqualByComparingTo("0.2029");
    assertThat(outcome.sign().status()).isEqualTo(SignStatus.UNDER_REVIEW);
    assertThat(outcome.submissionStatus()).isEqualTo(SubmissionStatus.ACCEPTED);
    assertThat(outcome.events())
        .extracting(SignEvent::type)
        .containsExactly(SignEventType.MARKED_UNDER_REVIEW);
  }

  @Test
  @DisplayName("UT-B07: mã thứ hai chiếm 35% trọng số → Cần xem kỹ")
  void utB07() {
    world.submit(sub("s1", "u1").p("0.65").build());
    ClusteringOutcome outcome =
        world.submit(sub("s2", "u2").code(OTHER_CODE).p("0.35").at(north(3)).build());

    SignState sign = outcome.sign();
    assertThat(sign.signTypeCode()).isEqualTo(CODE);
    assertThat(sign.suggestion()).isEqualTo(Suggestion.INSPECT);
    // α = 0,5 + 0,65 = 1,15; β = 0,5 + 0,35 = 0,85.
    assertThat(sign.confidence()).isEqualByComparingTo("0.5750");
    assertThat(sign.codeWeights()).containsOnlyKeys(CODE, OTHER_CODE);
    // Vị trí chỉ theo đề xuất mang mã dẫn đầu.
    assertThat(sign.location()).isEqualTo(ORIGIN);
  }

  @Test
  @DisplayName("Mã dẫn đầu đổi → LEADING_CODE_CHANGED kèm giá trị trước và sau")
  void leadingCodeChanged() {
    world.submit(sub("s1", "u1").p("0.6").build());
    ClusteringOutcome outcome =
        world.submit(sub("s2", "u2").code(OTHER_CODE).p("0.9").at(north(4)).build());

    assertThat(outcome.sign().signTypeCode()).isEqualTo(OTHER_CODE);
    assertThat(outcome.sign().location()).isEqualTo(north(4));
    assertThat(outcome.events())
        .singleElement()
        .satisfies(e -> {
          assertThat(e.type()).isEqualTo(SignEventType.LEADING_CODE_CHANGED);
          assertThat(e.before()).containsExactlyEntriesOf(java.util.Map.of("signTypeCode", CODE));
          assertThat(e.after())
              .containsExactlyEntriesOf(java.util.Map.of("signTypeCode", OTHER_CODE));
        });
  }

  @Test
  @DisplayName("Hoà trọng số: mã đến trước giữ vị trí dẫn đầu, không sinh LEADING_CODE_CHANGED")
  void tieKeepsFirstCode() {
    world.submit(sub("s1", "u1").build());
    ClusteringOutcome outcome =
        world.submit(sub("s2", "u2").code(OTHER_CODE).at(north(2)).at(hours(1)).build());

    assertThat(outcome.sign().signTypeCode()).isEqualTo(CODE);
    assertThat(outcome.events()).isEmpty();
    // s2 = 0,5 ≥ 0,3.
    assertThat(outcome.sign().suggestion()).isEqualTo(Suggestion.INSPECT);
  }

  @Test
  @DisplayName("Hoà trọng số: mã có nhiều người hơn dẫn đầu")
  void tiePrefersMoreSubmitters() {
    world.submit(sub("s1", "u1").build());
    world.submit(
        sub("s2", "u2").code(OTHER_CODE).flags(SubmissionFlag.SPLIT_VOTE).at(hours(1)).build());
    ClusteringOutcome outcome =
        world.submit(
            sub("s3", "u3").code(OTHER_CODE).flags(SubmissionFlag.SPLIT_VOTE).at(hours(2)).build());

    assertThat(outcome.sign().codeWeights().get(OTHER_CODE))
        .isEqualTo(new CodeWeight(new BigDecimal("0.9000"), 2));
    assertThat(outcome.sign().signTypeCode()).isEqualTo(OTHER_CODE);
  }

  @Test
  @DisplayName("UT-B09: 3 người, p = 0,95, tự duyệt bật → APPROVED, AUTO_APPROVED, SYSTEM")
  void utB09() {
    World auto = new World(engine(ClusteringConfig.DEFAULTS.withAutoApprove(true)));
    auto.submit(sub("s1", "u1").p("0.95").build());
    ClusteringOutcome second = auto.submit(sub("s2", "u2").p("0.95").at(north(3)).build());
    assertThat(second.sign().status()).isEqualTo(SignStatus.PENDING);
    assertThat(second.sign().suggestion()).isEqualTo(Suggestion.APPROVE);

    java.time.Instant now = hours(1);
    ClusteringOutcome third = auto.submit(sub("s3", "u3").p("0.95").at(north(6)).build(), now);

    SignState sign = third.sign();
    assertThat(sign.status()).isEqualTo(SignStatus.APPROVED);
    assertThat(sign.suggestion()).isNull();
    assertThat(sign.reviewedAt()).isEqualTo(now);
    assertThat(sign.moderatorBoost()).isEqualByComparingTo("0");
    // α = 0,5 + 2,85 = 3,35; β = 0,5.
    assertThat(sign.confidence()).isEqualByComparingTo("0.8701");
    assertThat(third.submissionStatus()).isEqualTo(SubmissionStatus.ACCEPTED);
    assertThat(third.memberUpdates())
        .containsExactly(
            new MemberUpdate("s1", SubmissionStatus.ACCEPTED, null),
            new MemberUpdate("s2", SubmissionStatus.ACCEPTED, null));
    assertThat(sign.members()).allMatch(m -> m.status() == SubmissionStatus.ACCEPTED);
    assertThat(third.events())
        .singleElement()
        .satisfies(e -> {
          assertThat(e.type()).isEqualTo(SignEventType.AUTO_APPROVED);
          assertThat(e.actorType()).isEqualTo("SYSTEM");
          assertThat(e.before()).containsEntry("status", "PENDING");
          assertThat(e.after())
              .containsEntry("status", "APPROVED")
              .containsEntry("signTypeCode", CODE);
        });
  }

  @Test
  @DisplayName("UT-B10: như UT-B09, tự duyệt tắt → vẫn PENDING")
  void utB10() {
    for (int i = 1; i <= 3; i++) {
      world.submit(sub("s" + i, "u" + i).p("0.95").at(north(i)).build());
    }

    SignState sign = world.outcomes.getLast().sign();
    assertThat(sign.status()).isEqualTo(SignStatus.PENDING);
    assertThat(sign.suggestion()).isEqualTo(Suggestion.APPROVE);
    assertThat(sign.confidence()).isEqualByComparingTo("0.8701");
    assertThat(world.outcomes.getLast().events()).isEmpty();
  }

  @Test
  @DisplayName("Tự duyệt: mã khác trong biển bị REJECTED khi biển được duyệt")
  void autoApproveRejectsOtherCodes() {
    World auto = new World(engine(ClusteringConfig.DEFAULTS.withAutoApprove(true)));
    auto.submit(sub("s0", "u0").code(OTHER_CODE).p("0.2").flags(SubmissionFlag.SPLIT_VOTE).build());
    for (int i = 1; i <= 3; i++) {
      auto.submit(sub("s" + i, "u" + i).p("1").at(north(i)).build());
    }
    ClusteringOutcome last = auto.outcomes.getLast();
    // W: CODE 3,0, OTHER 0,1 → s2 = 0,1/3,1 < 0,1; điểm 3,5/4,1 = 0,8537.
    assertThat(last.sign().status()).isEqualTo(SignStatus.APPROVED);
    assertThat(last.memberUpdates())
        .contains(
            new MemberUpdate("s0", SubmissionStatus.REJECTED, ClusteringEngine.REASON_OTHER_CODE));
  }

  @Test
  @DisplayName("Tự duyệt bật nhưng s2 ≥ 0,1 hoặc n < 3 → không tự duyệt")
  void autoApproveThresholds() {
    World auto = new World(engine(ClusteringConfig.DEFAULTS.withAutoApprove(true)));
    auto.submit(sub("s0", "u0").code(OTHER_CODE).p("0.8").build());
    for (int i = 1; i <= 7; i++) {
      auto.submit(sub("s" + i, "u" + i).p("1").at(north(i % 3)).build());
    }
    // W: CODE 7, OTHER 0,8 → s2 = 0,1026; điểm 7,5/8,8 = 0,8523; n = 8 → Nên duyệt, không tự duyệt.
    SignState sign = auto.outcomes.getLast().sign();
    assertThat(sign.confidence()).isEqualByComparingTo("0.8523");
    assertThat(sign.suggestion()).isEqualTo(Suggestion.APPROVE);
    assertThat(sign.status()).isEqualTo(SignStatus.PENDING);

    World two = new World(engine(ClusteringConfig.DEFAULTS.withAutoApprove(true)));
    two.submit(sub("s1", "u1").p("1").build());
    ClusteringOutcome o = two.submit(sub("s2", "u2").p("1").build());
    assertThat(o.sign().confidence()).isEqualByComparingTo("0.8333");
    assertThat(o.sign().status()).isEqualTo(SignStatus.PENDING);
  }

  @Test
  @DisplayName("UT-B12: cùng mã của biển bị từ chối 15 ngày trước → gán vào hồ sơ bị từ chối")
  void utB12() {
    SignState rejected =
        rejectedSign("r", CODE, north(5), 90, T0.minus(Duration.ofDays(15))).build();
    world.with(rejected);

    ClusteringOutcome outcome = world.submit(sub("s1", "u1").build());

    assertThat(outcome.sign()).isSameAs(rejected);
    assertThat(outcome.signCreated()).isFalse();
    assertThat(outcome.signChanged()).isFalse();
    assertThat(outcome.submissionStatus()).isEqualTo(SubmissionStatus.REJECTED);
    assertThat(outcome.rejectReason()).isNotBlank();
    assertThat(outcome.submissionWeight()).isEqualByComparingTo("0");
    assertThat(outcome.events()).isEmpty();
  }

  @Test
  @DisplayName("UT-B12: mã khác với biển bị từ chối → tạo biển như thường")
  void utB12OtherCode() {
    world.with(rejectedSign("r", CODE, north(5), 90, T0.minus(Duration.ofDays(15))).build());

    ClusteringOutcome outcome = world.submit(sub("s1", "u1").code(OTHER_CODE).build());

    assertThat(outcome.signCreated()).isTrue();
    assertThat(outcome.sign().flags()).isEmpty();
    assertThat(outcome.submissionStatus()).isEqualTo(SubmissionStatus.GROUPED);
  }

  @Test
  @DisplayName("Biển bị từ chối đúng 30 ngày còn chặn; 30 ngày + 1 ms và 31 ngày thì không")
  void rejectedWindowBoundary() {
    assertThat(submitNearRejected(Duration.ofDays(30)).submissionStatus())
        .isEqualTo(SubmissionStatus.REJECTED);
    assertThat(submitNearRejected(Duration.ofDays(30).plusMillis(1)).signCreated()).isTrue();
    assertThat(submitNearRejected(Duration.ofDays(31)).signCreated()).isTrue();
  }

  private static ClusteringOutcome submitNearRejected(Duration age) {
    World w = new World(engine());
    w.with(rejectedSign("r", CODE, north(5), 90, T0.minus(age)).build());
    return w.submit(sub("s1", "u1").build());
  }

  @Test
  @DisplayName("Bước 3 trước bước 4: biển PENDING thắng biển bị từ chối gần hơn")
  void pendingBeatsRejected() {
    world.with(rejectedSign("r", CODE, north(2), 90, T0.minus(Duration.ofDays(3))).build());
    world.with(pendingSign("p", OTHER_CODE, north(15), 90).build());

    assertThat(world.submit(sub("s1", "u1").build()).sign().id()).isEqualTo("p");
  }

  @Test
  @DisplayName("Bước 4 trước bước 5: biển bị từ chối cùng mã thắng biển đã duyệt khác mã")
  void rejectedBeatsDiffersFromApproved() {
    world.with(rejectedSign("r", CODE, north(10), 90, T0.minus(Duration.ofDays(3))).build());
    world.with(approvedSign("a", OTHER_CODE, north(1), 90).build());

    ClusteringOutcome outcome = world.submit(sub("s1", "u1").build());

    assertThat(outcome.sign().id()).isEqualTo("r");
    assertThat(outcome.submissionStatus()).isEqualTo(SubmissionStatus.REJECTED);
  }

  @Test
  @DisplayName("Bước 2 trước bước 3: biển đã duyệt cùng mã thắng biển PENDING gần hơn")
  void publishedBeatsPending() {
    world.with(pendingSign("p", CODE, north(1), 90).build());
    world.with(approvedSign("a", CODE, north(18), 90).build());

    assertThat(world.submit(sub("s1", "u1").build()).sign().id()).isEqualTo("a");
  }

  @Test
  @DisplayName("Biển PENDING gần nhất được chọn; cùng khoảng cách thì id nhỏ hơn")
  void nearestPending() {
    world.with(pendingSign("far", CODE, north(12), 90).build());
    world.with(pendingSign("near", OTHER_CODE, north(5), 90).build());
    assertThat(world.submit(sub("s1", "u1").build()).sign().id()).isEqualTo("near");

    World tie = new World(engine());
    tie.with(pendingSign("b", CODE, north(5), 90).build());
    tie.with(pendingSign("a", CODE, north(5), 90).build());
    assertThat(tie.submit(sub("s1", "u1").build()).sign().id()).isEqualTo("a");
  }

  @Test
  @DisplayName("Bỏ qua biển khác nhóm, biển REMOVED, biển ngoài 20 m, lệch hướng > 45°")
  void ineligibleCandidates() {
    world.with(pendingSign("other-category", CODE, north(1), 90).categoryCode("WARNING").build());
    world.with(approvedSign("removed", CODE, north(1), 90).status(SignStatus.REMOVED).build());
    world.with(pendingSign("too-far", CODE, north(20.01), 90).build());
    world.with(pendingSign("wrong-heading", CODE, north(1), 136).build());

    ClusteringOutcome outcome = world.submit(sub("s1", "u1").build());

    assertThat(outcome.signCreated()).isTrue();
    assertThat(outcome.sign().flags()).isEmpty();
  }

  @Test
  @DisplayName("Biên 20 m và 45° vẫn khớp")
  void boundariesMatch() {
    world.with(pendingSign("edge", CODE, north(19.99), 135).build());

    assertThat(world.submit(sub("s1", "u1").heading(90).build()).sign().id()).isEqualTo("edge");
  }

  @Test
  @DisplayName("Lệch hướng 350° và 10° là 20° → cùng biển")
  void headingWrapsAround() {
    world.with(pendingSign("p", CODE, north(3), 350).build());

    assertThat(world.submit(sub("s1", "u1").heading(10).build()).sign().id()).isEqualTo("p");
  }

  @Test
  @DisplayName(
      "memberUpdates: APPROVED → cùng mã ACCEPTED, khác mã REJECTED; REJECTED → mọi đề xuất REJECTED")
  void memberUpdatesOnStatusChange() {
    SignState sign =
        approvedSign("a", CODE, ORIGIN, 90)
            .members(
                List.of(
                    member("m1", "u1", CODE, "0.9", ORIGIN, T0),
                    member("m2", "u2", OTHER_CODE, "0.9", ORIGIN, T0),
                    new MemberSubmission(
                        "m3", "u3", CODE, BigDecimal.ONE, ORIGIN, T0, SubmissionStatus.ACCEPTED)))
            .build();

    assertThat(ClusteringEngine.memberUpdates(sign))
        .containsExactly(
            new MemberUpdate("m1", SubmissionStatus.ACCEPTED, null),
            new MemberUpdate("m2", SubmissionStatus.REJECTED, ClusteringEngine.REASON_OTHER_CODE));

    SignState rejected = sign.toBuilder().status(SignStatus.REJECTED).reviewedAt(T0).build();
    assertThat(ClusteringEngine.memberUpdates(rejected))
        .extracting(MemberUpdate::submissionId, MemberUpdate::status)
        .containsExactly(
            org.assertj.core.groups.Tuple.tuple("m1", SubmissionStatus.REJECTED),
            org.assertj.core.groups.Tuple.tuple("m2", SubmissionStatus.REJECTED),
            org.assertj.core.groups.Tuple.tuple("m3", SubmissionStatus.REJECTED));

    assertThat(ClusteringEngine.memberUpdates(sign.toBuilder().status(SignStatus.PENDING).build()))
        .isEmpty();
  }

  private static org.assertj.core.data.Offset<Double> within() {
    return org.assertj.core.data.Offset.offset(1e-12);
  }
}
