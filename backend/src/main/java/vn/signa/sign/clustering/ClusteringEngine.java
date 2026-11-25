package vn.signa.sign.clustering;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Hàm thuần gom một đề xuất vào biển, áp đánh giá và tính trạng thái đề xuất thành viên ({@code
 * docs/04 §6}). Không đọc đồng hồ, không I/O: thời điểm và ID biển mới do bên gọi cung cấp.
 */
public final class ClusteringEngine {

  static final String REASON_REJECTED_SIGN =
      "Biển cùng mã ở vị trí này đã bị từ chối trong 30 ngày";
  static final String REASON_OTHER_CODE = "Biển được duyệt với mã khác";
  static final String REASON_SIGN_REJECTED = "Biển bị từ chối";

  private final ClusteringConfig config;
  private final Supplier<String> newSignId;

  /**
   * @param newSignId sinh ID cho biển mới, ví dụ {@code () -> UUID.randomUUID().toString()}
   */
  public ClusteringEngine(ClusteringConfig config, Supplier<String> newSignId) {
    this.config = Objects.requireNonNull(config, "config");
    this.newSignId = Objects.requireNonNull(newSignId, "newSignId");
  }

  public ClusteringConfig config() {
    return config;
  }

  /**
   * Gom một đề xuất.
   *
   * @param candidates biển đã lọc thô trong bán kính, kèm đề xuất thành viên
   * @param now thời điểm xử lý, ghi vào {@code reviewedAt} khi tự duyệt
   */
  public ClusteringOutcome process(
      SubmissionInput submission, List<SignState> candidates, Instant now) {
    Assignment.Decision decision = Assignment.decide(submission, candidates, config);
    if (decision.kind() == Assignment.Kind.REJECT) {
      return new ClusteringOutcome(
          decision.target(),
          false,
          false,
          BigDecimal.ZERO.setScale(Weights.SCALE),
          SubmissionStatus.REJECTED,
          REASON_REJECTED_SIGN,
          List.of(),
          List.of());
    }
    boolean created = decision.kind() == Assignment.Kind.CREATE;
    SignState base = created ? newSign(submission, decision.related()) : decision.target();

    BigDecimal weight = Weights.of(submission, base.members(), config);
    SignState withSubmission = addSubmission(base, submission, weight);
    SignState sign = recompute(withSubmission, created ? 0 : base.version() + 1, true);

    List<SignEvent> events = new ArrayList<>();
    if (created) {
      events.add(new SignEvent(SignEventType.CREATED, null, snapshot(sign), null));
    } else if (!sign.signTypeCode().equals(base.signTypeCode())) {
      events.add(
          new SignEvent(
              SignEventType.LEADING_CODE_CHANGED,
              Map.of("signTypeCode", base.signTypeCode()),
              Map.of("signTypeCode", sign.signTypeCode()),
              null));
    }
    if (!created && !sign.location().equals(base.location())) {
      events.add(
          new SignEvent(
              SignEventType.LOCATION_RECOMPUTED,
              latLng(base.location()),
              latLng(sign.location()),
              null));
    }

    SubmissionStatus status =
        decision.kind() == Assignment.Kind.CONFIRM && base.status() == SignStatus.APPROVED
            ? SubmissionStatus.ACCEPTED
            : SubmissionStatus.GROUPED;
    List<MemberUpdate> memberUpdates = List.of();
    String rejectReason = null;

    if (Suggestions.shouldAutoApprove(sign, sign.signTypeCode(), config)) {
      SignState approved =
          sign.toBuilder().status(SignStatus.APPROVED).suggestion(null).reviewedAt(now).build();
      List<MemberUpdate> all = memberUpdates(approved);
      sign = applyMemberUpdates(approved, all);
      MemberUpdate own =
          all.stream()
              .filter(u -> u.submissionId().equals(submission.id()))
              .findFirst()
              .orElseThrow();
      status = own.status();
      rejectReason = own.rejectReason();
      memberUpdates = all.stream().filter(u -> !u.submissionId().equals(submission.id())).toList();
      events.add(
          new SignEvent(
              SignEventType.AUTO_APPROVED,
              Map.of("status", SignStatus.PENDING.name()),
              statusSnapshot(sign),
              null));
    } else {
      sign = setMemberStatus(sign, submission.id(), status);
    }

    Optional<SignEvent> review = reviewEvent(sign);
    if (review.isPresent()) {
      sign = markUnderReview(sign);
      events.add(review.get());
    }
    return new ClusteringOutcome(
        sign, created, true, weight, status, rejectReason, memberUpdates, events);
  }

  /**
   * Áp một đánh giá (FR-09) lên biển {@code APPROVED} hoặc {@code UNDER_REVIEW}: cập nhật {@code
   * R+}, {@code R−}, {@code deny_since_review}, điểm, và chuyển {@code UNDER_REVIEW} khi cần
   * (FR-22).
   */
  public RatingOutcome applyRating(SignState sign, RatingChange change) {
    if (!sign.status().isPublished()) {
      throw new IllegalArgumentException(
          "Chỉ đánh giá biển APPROVED hoặc UNDER_REVIEW, biển "
              + sign.id()
              + " là "
              + sign.status());
    }
    int confirm = sign.confirmCount();
    int deny = sign.denyCount();
    int denySinceReview = sign.denySinceReview();
    if (change.previous() == RatingValue.CONFIRM) {
      confirm--;
    } else if (change.previous() == RatingValue.DENY) {
      deny--;
      if (sinceReview(change.previousAt(), sign.reviewedAt())) {
        denySinceReview--;
      }
    }
    if (change.current() == RatingValue.CONFIRM) {
      confirm++;
    } else {
      deny++;
      if (sinceReview(change.at(), sign.reviewedAt())) {
        denySinceReview++;
      }
    }
    SignState rated =
        sign.toBuilder()
            .confirmCount(confirm)
            .denyCount(deny)
            .denySinceReview(denySinceReview)
            .build();
    SignState updated = recompute(rated, sign.version() + 1, false);
    List<SignEvent> events = new ArrayList<>();
    Optional<SignEvent> review = reviewEvent(updated);
    if (review.isPresent()) {
      updated = markUnderReview(updated);
      events.add(review.get());
    }
    return new RatingOutcome(updated, events);
  }

  /**
   * Trạng thái mới của đề xuất thành viên sau khi biển đổi trạng thái: {@code APPROVED} thì cùng mã
   * {@code ACCEPTED}, khác mã {@code REJECTED}; {@code REJECTED} thì mọi đề xuất {@code REJECTED}.
   * Chỉ trả đề xuất có trạng thái thay đổi.
   */
  public static List<MemberUpdate> memberUpdates(SignState sign) {
    List<MemberUpdate> updates = new ArrayList<>();
    for (MemberSubmission m : sign.members()) {
      MemberUpdate target =
          switch (sign.status()) {
            case APPROVED ->
                m.signTypeCode().equals(sign.signTypeCode())
                    ? new MemberUpdate(m.id(), SubmissionStatus.ACCEPTED, null)
                    : new MemberUpdate(m.id(), SubmissionStatus.REJECTED, REASON_OTHER_CODE);
            case REJECTED ->
                new MemberUpdate(m.id(), SubmissionStatus.REJECTED, REASON_SIGN_REJECTED);
            default -> null;
          };
      if (target != null && target.status() != m.status()) {
        updates.add(target);
      }
    }
    return updates;
  }

  private SignState newSign(SubmissionInput submission, SignState related) {
    return SignState.builder()
        .id(newSignId.get())
        .status(SignStatus.PENDING)
        .signTypeCode(submission.signTypeCode())
        .categoryCode(submission.categoryCode())
        .location(submission.location())
        .headingDeg(submission.headingDeg())
        .flags(
            related == null
                ? EnumSet.noneOf(SignFlag.class)
                : EnumSet.of(SignFlag.DIFFERS_FROM_APPROVED))
        .relatedSignId(related == null ? null : related.id())
        .build();
  }

  private static SignState addSubmission(
      SignState sign, SubmissionInput submission, BigDecimal weight) {
    String code = submission.signTypeCode();
    boolean newToSign =
        sign.members().stream().noneMatch(m -> m.userId().equals(submission.userId()));
    boolean newToCode =
        sign.members().stream()
            .noneMatch(
                m -> m.userId().equals(submission.userId()) && m.signTypeCode().equals(code));
    CodeWeight old = sign.codeWeights().get(code);
    CodeWeight updated =
        old == null
            ? new CodeWeight(weight, 1)
            : new CodeWeight(old.weight().add(weight), old.submitterCount() + (newToCode ? 1 : 0));
    return sign.toBuilder()
        .codeWeight(code, updated)
        .distinctSubmitters(sign.distinctSubmitters() + (newToSign ? 1 : 0))
        .addMember(
            new MemberSubmission(
                submission.id(),
                submission.userId(),
                code,
                weight,
                submission.location(),
                submission.receivedAt(),
                SubmissionStatus.GROUPED))
        .build();
  }

  /**
   * Tính lại mã dẫn đầu, điểm, gợi ý và (nếu {@code withLocation}) vị trí. Vị trí chỉ đổi khi khác
   * vị trí cũ sau khi làm tròn {@value #LOCATION_SCALE} chữ số, để mọi thay đổi vị trí đã lưu đều
   * có sự kiện {@code LOCATION_RECOMPUTED} (FR-23).
   */
  private SignState recompute(SignState sign, int version, boolean withLocation) {
    String leading = Scoring.leadingCode(sign);
    GeoPoint location = sign.location();
    if (withLocation) {
      GeoPoint median = Location.of(sign, leading);
      if (!sameLocation(median, location)) {
        location = median;
      }
    }
    SignState coded =
        sign.toBuilder().signTypeCode(leading).location(location).version(version).build();
    BigDecimal score = Scoring.score(coded, leading);
    return coded.toBuilder()
        .confidence(score)
        .suggestion(Suggestions.suggest(coded, leading, score, config))
        .build();
  }

  private Optional<SignEvent> reviewEvent(SignState sign) {
    return Suggestions.reviewReason(sign, config)
        .map(reason ->
            new SignEvent(
                SignEventType.MARKED_UNDER_REVIEW,
                Map.of("status", SignStatus.APPROVED.name(), "confidence", sign.confidence()),
                Map.of("status", SignStatus.UNDER_REVIEW.name(), "confidence", sign.confidence()),
                reason));
  }

  private static SignState markUnderReview(SignState sign) {
    return sign.toBuilder().status(SignStatus.UNDER_REVIEW).suggestion(Suggestion.INSPECT).build();
  }

  private static boolean sinceReview(Instant at, Instant reviewedAt) {
    return reviewedAt == null || !at.isBefore(reviewedAt);
  }

  private static SignState setMemberStatus(SignState sign, String id, SubmissionStatus status) {
    return applyMemberUpdates(sign, List.of(new MemberUpdate(id, status, null)));
  }

  private static SignState applyMemberUpdates(SignState sign, List<MemberUpdate> updates) {
    Map<String, SubmissionStatus> byId = new LinkedHashMap<>();
    updates.forEach(u -> byId.put(u.submissionId(), u.status()));
    List<MemberSubmission> members =
        sign.members().stream()
            .map(m -> byId.containsKey(m.id()) ? m.withStatus(byId.get(m.id())) : m)
            .toList();
    return sign.toBuilder().members(members).build();
  }

  /** Số chữ số thập phân của độ khi so sánh vị trí. */
  static final int LOCATION_SCALE = 6;

  static boolean sameLocation(GeoPoint a, GeoPoint b) {
    return round(a.lat()).compareTo(round(b.lat())) == 0
        && round(a.lng()).compareTo(round(b.lng())) == 0;
  }

  private static BigDecimal round(double degrees) {
    return BigDecimal.valueOf(degrees).setScale(LOCATION_SCALE, java.math.RoundingMode.HALF_UP);
  }

  private static Map<String, Object> latLng(GeoPoint point) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("lat", point.lat());
    m.put("lng", point.lng());
    return m;
  }

  private static Map<String, Object> snapshot(SignState sign) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("status", sign.status().name());
    m.put("signTypeCode", sign.signTypeCode());
    m.put("categoryCode", sign.categoryCode());
    m.put("lat", sign.location().lat());
    m.put("lng", sign.location().lng());
    m.put("headingDeg", sign.headingDeg());
    m.put("flags", sign.flags().stream().map(Enum::name).sorted().toList());
    m.put("relatedSignId", sign.relatedSignId());
    return m;
  }

  private static Map<String, Object> statusSnapshot(SignState sign) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("status", sign.status().name());
    m.put("signTypeCode", sign.signTypeCode());
    m.put("confidence", sign.confidence());
    return m;
  }
}
