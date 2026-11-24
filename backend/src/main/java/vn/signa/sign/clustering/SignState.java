package vn.signa.sign.clustering;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Trạng thái một biển ({@code signs} cùng {@code sign_code_weights}) và các đề xuất thành viên.
 *
 * @param signTypeCode mã dẫn đầu, hoặc mã kiểm duyệt viên đã chốt
 * @param codeWeights {@code W_c} và {@code submitter_count} theo từng mã
 * @param moderatorBoost {@code M} trong công thức điểm
 * @param confidence điểm tin cậy, 4 chữ số thập phân
 * @param suggestion gợi ý; {@code null} khi biển không chờ kiểm duyệt
 * @param relatedSignId biển đã duyệt mà biển này khác mã
 * @param reviewedAt thời điểm quyết định gần nhất; bắt buộc với {@code REJECTED}
 * @param members các đề xuất đã gán vào biển
 * @param weightsSinceReview tổng {@code w} theo mã của các đề xuất có {@code receivedAt >
 *     reviewedAt}; rỗng khi {@code reviewedAt = null}. Dùng cho {@code β} sau quyết định của kiểm
 *     duyệt viên (docs/04 §6). {@code denySinceReview} là {@code R−} cùng mốc
 */
public record SignState(
    String id,
    SignStatus status,
    String signTypeCode,
    String categoryCode,
    GeoPoint location,
    Integer headingDeg,
    boolean locationLocked,
    Map<String, CodeWeight> codeWeights,
    int distinctSubmitters,
    int confirmCount,
    int denyCount,
    int denySinceReview,
    BigDecimal moderatorBoost,
    BigDecimal confidence,
    Suggestion suggestion,
    Set<SignFlag> flags,
    String relatedSignId,
    Instant reviewedAt,
    int version,
    List<MemberSubmission> members,
    Map<String, BigDecimal> weightsSinceReview) {

  public SignState {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(status, "status");
    Objects.requireNonNull(signTypeCode, "signTypeCode");
    Objects.requireNonNull(categoryCode, "categoryCode");
    Objects.requireNonNull(location, "location");
    Objects.requireNonNull(moderatorBoost, "moderatorBoost");
    Objects.requireNonNull(confidence, "confidence");
    if (headingDeg != null && (headingDeg < 0 || headingDeg > 359)) {
      throw new IllegalArgumentException("headingDeg ngoài 0–359: " + headingDeg);
    }
    if (distinctSubmitters < 0 || confirmCount < 0 || denyCount < 0 || denySinceReview < 0) {
      throw new IllegalArgumentException("Bộ đếm của biển " + id + " âm");
    }
    if (moderatorBoost.signum() < 0) {
      throw new IllegalArgumentException("moderatorBoost âm: " + moderatorBoost);
    }
    if (status == SignStatus.REJECTED && reviewedAt == null) {
      throw new IllegalArgumentException("Biển REJECTED " + id + " thiếu reviewedAt");
    }
    codeWeights = Map.copyOf(codeWeights == null ? Map.of() : codeWeights);
    flags = flags == null || flags.isEmpty() ? Set.of() : Set.copyOf(EnumSet.copyOf(flags));
    members = List.copyOf(members == null ? List.of() : members);
    weightsSinceReview = Map.copyOf(weightsSinceReview == null ? Map.of() : weightsSinceReview);
    if (reviewedAt == null && !weightsSinceReview.isEmpty()) {
      throw new IllegalArgumentException("Biển " + id + " chưa duyệt nhưng có weightsSinceReview");
    }
    if (weightsSinceReview.values().stream().anyMatch(w -> w.signum() < 0)) {
      throw new IllegalArgumentException("weightsSinceReview âm ở biển " + id);
    }
  }

  /** Tổng {@code Σ W_c}. */
  public BigDecimal totalWeight() {
    return codeWeights.values().stream()
        .map(CodeWeight::weight)
        .reduce(BigDecimal.ZERO, BigDecimal::add);
  }

  /** Trọng số {@code W_c} của một mã, 0 nếu chưa có. */
  public BigDecimal weightOf(String code) {
    CodeWeight cw = codeWeights.get(code);
    return cw == null ? BigDecimal.ZERO : cw.weight();
  }

  public static Builder builder() {
    return new Builder();
  }

  public Builder toBuilder() {
    Builder b = new Builder();
    b.id = id;
    b.status = status;
    b.signTypeCode = signTypeCode;
    b.categoryCode = categoryCode;
    b.location = location;
    b.headingDeg = headingDeg;
    b.locationLocked = locationLocked;
    b.codeWeights = new HashMap<>(codeWeights);
    b.distinctSubmitters = distinctSubmitters;
    b.confirmCount = confirmCount;
    b.denyCount = denyCount;
    b.denySinceReview = denySinceReview;
    b.moderatorBoost = moderatorBoost;
    b.confidence = confidence;
    b.suggestion = suggestion;
    b.flags = flags.isEmpty() ? EnumSet.noneOf(SignFlag.class) : EnumSet.copyOf(flags);
    b.relatedSignId = relatedSignId;
    b.reviewedAt = reviewedAt;
    b.version = version;
    b.members = new ArrayList<>(members);
    b.weightsSinceReview = new HashMap<>(weightsSinceReview);
    return b;
  }

  /** Dựng {@link SignState}; giá trị mặc định khớp mặc định cột trong {@code docs/06 §6}. */
  public static final class Builder {
    private String id;
    private SignStatus status = SignStatus.PENDING;
    private String signTypeCode;
    private String categoryCode;
    private GeoPoint location;
    private Integer headingDeg;
    private boolean locationLocked;
    private Map<String, CodeWeight> codeWeights = new HashMap<>();
    private int distinctSubmitters;
    private int confirmCount;
    private int denyCount;
    private int denySinceReview;
    private BigDecimal moderatorBoost = BigDecimal.ZERO;
    private BigDecimal confidence = BigDecimal.ZERO;
    private Suggestion suggestion;
    private Set<SignFlag> flags = EnumSet.noneOf(SignFlag.class);
    private String relatedSignId;
    private Instant reviewedAt;
    private int version;
    private List<MemberSubmission> members = new ArrayList<>();
    private Map<String, BigDecimal> weightsSinceReview = new HashMap<>();

    private Builder() {}

    public Builder id(String v) {
      id = v;
      return this;
    }

    public Builder status(SignStatus v) {
      status = v;
      return this;
    }

    public Builder signTypeCode(String v) {
      signTypeCode = v;
      return this;
    }

    public Builder categoryCode(String v) {
      categoryCode = v;
      return this;
    }

    public Builder location(GeoPoint v) {
      location = v;
      return this;
    }

    public Builder headingDeg(Integer v) {
      headingDeg = v;
      return this;
    }

    public Builder locationLocked(boolean v) {
      locationLocked = v;
      return this;
    }

    public Builder codeWeights(Map<String, CodeWeight> v) {
      codeWeights = new HashMap<>(v);
      return this;
    }

    public Builder codeWeight(String code, CodeWeight v) {
      codeWeights.put(code, v);
      return this;
    }

    public Builder distinctSubmitters(int v) {
      distinctSubmitters = v;
      return this;
    }

    public Builder confirmCount(int v) {
      confirmCount = v;
      return this;
    }

    public Builder denyCount(int v) {
      denyCount = v;
      return this;
    }

    public Builder denySinceReview(int v) {
      denySinceReview = v;
      return this;
    }

    public Builder moderatorBoost(BigDecimal v) {
      moderatorBoost = v;
      return this;
    }

    public Builder confidence(BigDecimal v) {
      confidence = v;
      return this;
    }

    public Builder suggestion(Suggestion v) {
      suggestion = v;
      return this;
    }

    public Builder flags(Set<SignFlag> v) {
      flags = v.isEmpty() ? EnumSet.noneOf(SignFlag.class) : EnumSet.copyOf(v);
      return this;
    }

    public Builder relatedSignId(String v) {
      relatedSignId = v;
      return this;
    }

    public Builder reviewedAt(Instant v) {
      reviewedAt = v;
      return this;
    }

    public Builder version(int v) {
      version = v;
      return this;
    }

    public Builder members(List<MemberSubmission> v) {
      members = new ArrayList<>(v);
      return this;
    }

    public Builder addMember(MemberSubmission v) {
      members.add(v);
      return this;
    }

    public Builder weightsSinceReview(Map<String, BigDecimal> v) {
      weightsSinceReview = new HashMap<>(v);
      return this;
    }

    /** Cộng {@code w} của một đề xuất nhận sau {@code reviewedAt} vào mã {@code code}. */
    public Builder addWeightSinceReview(String code, BigDecimal w) {
      weightsSinceReview.merge(code, w, BigDecimal::add);
      return this;
    }

    /**
     * Quyết định mới (duyệt, giữ, tạo, tự duyệt) tại {@code at}: đặt {@code reviewedAt}, mọi bằng
     * chứng phản đối trước đó coi như đã xem xét nên {@code weightsSinceReview} rỗng và {@code
     * denySinceReview = 0}.
     */
    public Builder reviewed(Instant at) {
      reviewedAt = at;
      weightsSinceReview = new HashMap<>();
      denySinceReview = 0;
      return this;
    }

    public SignState build() {
      return new SignState(
          id,
          status,
          signTypeCode,
          categoryCode,
          location,
          headingDeg,
          locationLocked,
          codeWeights,
          distinctSubmitters,
          confirmCount,
          denyCount,
          denySinceReview,
          moderatorBoost,
          confidence,
          suggestion,
          flags,
          relatedSignId,
          reviewedAt,
          version,
          members,
          weightsSinceReview);
    }
  }
}
