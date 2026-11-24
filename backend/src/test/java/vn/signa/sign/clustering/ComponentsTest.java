package vn.signa.sign.clustering;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;
import static vn.signa.sign.clustering.Fixtures.CODE;
import static vn.signa.sign.clustering.Fixtures.ORIGIN;
import static vn.signa.sign.clustering.Fixtures.OTHER_CODE;
import static vn.signa.sign.clustering.Fixtures.T0;
import static vn.signa.sign.clustering.Fixtures.hours;
import static vn.signa.sign.clustering.Fixtures.member;
import static vn.signa.sign.clustering.Fixtures.north;
import static vn.signa.sign.clustering.Fixtures.pendingSign;
import static vn.signa.sign.clustering.Fixtures.sub;

import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Test từng thành phần: hình học, trọng số, điểm, gợi ý, vị trí, kiểm tra dữ liệu vào. */
class ComponentsTest {

  private static final ClusteringConfig CFG = ClusteringConfig.DEFAULTS;

  @Test
  @DisplayName("Lệch hướng theo vòng tròn: 350° và 10° là 20°")
  void headingDiff() {
    assertThat(Geo.headingDiff(350, 10)).isEqualTo(20);
    assertThat(Geo.headingDiff(10, 350)).isEqualTo(20);
    assertThat(Geo.headingDiff(0, 180)).isEqualTo(180);
    assertThat(Geo.headingDiff(90, 90)).isZero();
    assertThat(Geo.headingDiff(0, 45)).isEqualTo(45);
    assertThat(Geo.headingDiff(-10, 370)).isEqualTo(20);
    assertThat(Geo.headingsCompatible(null, 10, 45)).isTrue();
    assertThat(Geo.headingsCompatible(10, null, 45)).isTrue();
    assertThat(Geo.headingsCompatible(0, 46, 45)).isFalse();
  }

  @Test
  @DisplayName("Haversine: 8 m về bắc là 8 m; 0,0002° kinh độ ở Hà Nội khoảng 20,7 m")
  void distance() {
    assertThat(Geo.distanceMeters(ORIGIN, north(8))).isCloseTo(8.0, within(1e-6));
    assertThat(Geo.distanceMeters(ORIGIN, ORIGIN)).isZero();
    GeoPoint east = new GeoPoint(ORIGIN.lat(), ORIGIN.lng() + 0.0002);
    assertThat(Geo.distanceMeters(ORIGIN, east)).isCloseTo(20.75, within(0.01));
  }

  @Test
  @DisplayName("Trọng số: USER_EDITED p = 0,7; SPLIT_VOTE f = 0,5; làm tròn 4 chữ số")
  void weights() {
    assertThat(w(sub("s", "u").p("0.95").flags(SubmissionFlag.USER_EDITED))).isEqualTo("0.7000");
    assertThat(w(sub("s", "u").p("0.9").flags(SubmissionFlag.SPLIT_VOTE))).isEqualTo("0.4500");
    assertThat(
            w(sub("s", "u").p("0.2").flags(SubmissionFlag.SPLIT_VOTE, SubmissionFlag.USER_EDITED)))
        .isEqualTo("0.3500");
    assertThat(w(sub("s", "u").p("0.9123").flags(SubmissionFlag.SPLIT_VOTE))).isEqualTo("0.4562");
  }

  private static String w(Fixtures.SubmissionBuilder b) {
    return Weights.of(b.build(), List.of(), CFG).toPlainString();
  }

  @Test
  @DisplayName("Chống lặp: chỉ đề xuất có trọng số của cùng người, cửa sổ hai phía")
  void antiRepeat() {
    SubmissionInput at13h = sub("s", "u1").at(hours(13)).build();
    // Lần có trọng số lúc 0 giờ, lần không trọng số lúc 6 giờ → lúc 13 giờ có trọng số lại.
    List<MemberSubmission> members =
        List.of(
            member("m0", "u1", CODE, "0.9", ORIGIN, T0),
            member("m1", "u1", CODE, "0", ORIGIN, hours(6)),
            member("m2", "u2", CODE, "0.9", ORIGIN, hours(12.5)));
    assertThat(Weights.of(at13h, members, CFG)).isEqualByComparingTo("0.9");

    // Đề xuất cũ được xử lý muộn sau một đề xuất mới hơn có trọng số.
    SubmissionInput late = sub("s", "u1").at(T0).build();
    List<MemberSubmission> newer = List.of(member("m0", "u1", CODE, "0.9", ORIGIN, hours(1)));
    assertThat(Weights.of(late, newer, CFG)).isEqualByComparingTo("0");
  }

  @Test
  @DisplayName("Mã dẫn đầu khi hoà: submitter_count, rồi mã đến trước, rồi thứ tự từ điển")
  void leadingCodeTies() {
    SignState sameTime =
        pendingSign("p", OTHER_CODE, ORIGIN, 90)
            .codeWeight(OTHER_CODE, new CodeWeight(new BigDecimal("0.9"), 1))
            .codeWeight(CODE, new CodeWeight(new BigDecimal("0.9"), 1))
            .members(
                List.of(
                    member("m1", "u1", OTHER_CODE, "0.9", ORIGIN, T0),
                    member("m2", "u2", CODE, "0.9", ORIGIN, T0)))
            .build();
    assertThat(Scoring.leadingCode(sameTime)).isEqualTo(CODE);

    SignState otherFirst =
        sameTime.toBuilder()
            .members(
                List.of(
                    member("m1", "u1", OTHER_CODE, "0.9", ORIGIN, T0),
                    member("m2", "u2", CODE, "0.9", ORIGIN, hours(1))))
            .build();
    assertThat(Scoring.leadingCode(otherFirst)).isEqualTo(OTHER_CODE);

    SignState moreSubmitters =
        otherFirst.toBuilder().codeWeight(CODE, new CodeWeight(new BigDecimal("0.9"), 2)).build();
    assertThat(Scoring.leadingCode(moreSubmitters)).isEqualTo(CODE);

    SignState heavier =
        moreSubmitters.toBuilder()
            .codeWeight(OTHER_CODE, new CodeWeight(new BigDecimal("0.9001"), 1))
            .build();
    assertThat(Scoring.leadingCode(heavier)).isEqualTo(OTHER_CODE);

    SignState approved = heavier.toBuilder().status(SignStatus.APPROVED).signTypeCode(CODE).build();
    assertThat(Scoring.leadingCode(approved)).isEqualTo(CODE);
  }

  @Test
  @DisplayName("Điểm khi chưa có trọng số là 0,5; s2 = 0 khi tổng bằng 0")
  void emptySign() {
    SignState empty = pendingSign("p", CODE, ORIGIN, 90).build();
    assertThat(Scoring.score(empty, CODE)).isEqualByComparingTo("0.5");
    assertThat(Scoring.secondShareAtLeast(empty, CODE, new BigDecimal("0.3"))).isFalse();
    assertThat(Scoring.secondShareAtLeast(empty, CODE, BigDecimal.ZERO)).isTrue();
    assertThat(Scoring.leadingCode(empty)).isEqualTo(CODE);
  }

  @Test
  @DisplayName(
      "Gợi ý: s2 đúng 0,3 là Cần xem kỹ; 0,2999 thì không; điểm đúng 0,8 với n = 2 là Nên duyệt")
  void suggestionBoundaries() {
    SignState atThreshold = twoCodes("0.7", "0.3");
    assertThat(suggest(atThreshold)).isEqualTo(Suggestion.INSPECT);
    SignState below = twoCodes("0.7001", "0.2999");
    assertThat(suggest(below)).isEqualTo(Suggestion.WAIT);

    // α = 0,5 + 1,5 = 2; β = 0,5 → 0,8.
    SignState eighty =
        pendingSign("p", CODE, ORIGIN, 90)
            .codeWeight(CODE, new CodeWeight(new BigDecimal("1.5"), 2))
            .distinctSubmitters(2)
            .build();
    assertThat(Scoring.score(eighty, CODE)).isEqualByComparingTo("0.8");
    assertThat(suggest(eighty)).isEqualTo(Suggestion.APPROVE);
    assertThat(suggest(eighty.toBuilder().distinctSubmitters(1).build()))
        .isEqualTo(Suggestion.WAIT);
    assertThat(
            suggest(
                eighty.toBuilder()
                    .flags(java.util.EnumSet.of(SignFlag.DIFFERS_FROM_APPROVED))
                    .build()))
        .isEqualTo(Suggestion.INSPECT);
    assertThat(suggest(eighty.toBuilder().status(SignStatus.UNDER_REVIEW).build()))
        .isEqualTo(Suggestion.INSPECT);
    assertThat(suggest(eighty.toBuilder().status(SignStatus.APPROVED).build())).isNull();
  }

  private static SignState twoCodes(String a, String b) {
    return pendingSign("p", CODE, ORIGIN, 90)
        .codeWeight(CODE, new CodeWeight(new BigDecimal(a), 1))
        .codeWeight(OTHER_CODE, new CodeWeight(new BigDecimal(b), 1))
        .distinctSubmitters(2)
        .build();
  }

  private static Suggestion suggest(SignState sign) {
    String leading = Scoring.leadingCode(sign);
    return Suggestions.suggest(sign, leading, Scoring.score(sign, leading), CFG);
  }

  @Test
  @DisplayName("Rà soát lại: chỉ biển APPROVED")
  void reviewOnlyApproved() {
    SignState pending =
        pendingSign("p", CODE, ORIGIN, 90).denySinceReview(5).confidence(BigDecimal.ZERO).build();
    assertThat(Suggestions.reviewReason(pending, CFG)).isEmpty();
  }

  @Test
  @DisplayName("Trung vị có trọng số theo vĩ độ và kinh độ riêng, chỉ trên mã dẫn đầu")
  void weightedMedian() {
    GeoPoint a = new GeoPoint(21.0001, 105.8003);
    GeoPoint b = new GeoPoint(21.0002, 105.8001);
    GeoPoint c = new GeoPoint(21.0003, 105.8002);
    SignState sign =
        pendingSign("p", CODE, ORIGIN, 90)
            .members(
                List.of(
                    member("m1", "u1", CODE, "0.9", a, T0),
                    member("m2", "u2", CODE, "0.9", b, T0),
                    member("m3", "u3", CODE, "0.9", c, T0),
                    member("m4", "u4", OTHER_CODE, "5", ORIGIN, T0)))
            .build();
    assertThat(Location.of(sign, CODE)).isEqualTo(new GeoPoint(21.0002, 105.8002));

    SignState heavyFirst =
        sign.toBuilder()
            .members(
                List.of(
                    member("m1", "u1", CODE, "2", a, T0),
                    member("m2", "u2", CODE, "0.9", b, T0),
                    member("m3", "u3", CODE, "0.9", c, T0)))
            .build();
    assertThat(Location.of(heavyFirst, CODE)).isEqualTo(new GeoPoint(21.0001, 105.8003));

    assertThat(Location.of(sign.toBuilder().locationLocked(true).build(), CODE)).isEqualTo(ORIGIN);
    SignState noWeight =
        sign.toBuilder().members(List.of(member("m1", "u1", CODE, "0", a, T0))).build();
    assertThat(Location.of(noWeight, CODE)).isEqualTo(ORIGIN);
  }

  @Test
  @DisplayName("Dữ liệu vào sai bị từ chối")
  void validation() {
    assertThatThrownBy(() -> new GeoPoint(91, 0)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new GeoPoint(0, 181)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new GeoPoint(Double.NaN, 0))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> sub("s", "u").p("1.01").build())
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> sub("s", "u").p("-0.1").build())
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> sub("s", "u").heading(360).build())
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> sub("s", "u").heading(-1).build())
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new CodeWeight(new BigDecimal("-1"), 0))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new CodeWeight(BigDecimal.ONE, -1))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() ->
            new MemberSubmission(
                "m", "u", CODE, new BigDecimal("-1"), ORIGIN, T0, SubmissionStatus.GROUPED))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> pendingSign("p", CODE, ORIGIN, 90).status(SignStatus.REJECTED).build())
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> pendingSign("p", CODE, ORIGIN, 400).build())
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> pendingSign("p", CODE, ORIGIN, 90).denyCount(-1).build())
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () -> pendingSign("p", CODE, ORIGIN, 90).moderatorBoost(new BigDecimal("-1")).build())
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(
            new SubmissionInput("s", "u", CODE, "C", BigDecimal.ONE, ORIGIN, null, null, T0)
                .flags())
        .isEmpty();
  }
}
