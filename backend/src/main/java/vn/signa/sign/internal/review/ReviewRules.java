package vn.signa.sign.internal.review;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.stream.Collectors;
import vn.signa.shared.error.ApiException;
import vn.signa.shared.error.ErrorCode;
import vn.signa.sign.clustering.ClusteringEngine;
import vn.signa.sign.clustering.CodeWeight;
import vn.signa.sign.clustering.GeoPoint;
import vn.signa.sign.clustering.MemberSubmission;
import vn.signa.sign.clustering.MemberUpdate;
import vn.signa.sign.clustering.Scoring;
import vn.signa.sign.clustering.SignState;
import vn.signa.sign.clustering.SignStatus;
import vn.signa.sign.clustering.SubmissionStatus;
import vn.signa.sign.clustering.Weights;

/**
 * Luật thuần của các thao tác phê duyệt và sửa biển (FR-31, FR-33). Không đọc hay ghi CSDL: nhận
 * trạng thái biển đã khoá, trả trạng thái mới, các thay đổi đề xuất thành viên, trọng số và sự kiện
 * lịch sử để dịch vụ ghi trong cùng giao dịch.
 */
final class ReviewRules {

  /** {@code M} khi kiểm duyệt viên đã duyệt hoặc tạo biển (docs/04 §6). */
  static final BigDecimal MODERATOR_BOOST = new BigDecimal("2");

  /** Độ dài cột {@code submissions.reject_reason}. */
  static final int MEMBER_REASON_MAX = 500;

  static final String REASON_OTHER_CODE = "Biển được duyệt với mã khác";
  static final String REASON_REMOVED = "Biển đã bị gỡ";

  private static final BigDecimal ZERO_WEIGHT = BigDecimal.ZERO.setScale(Weights.SCALE);

  private ReviewRules() {}

  /** Loại biển đã kiểm qua danh mục: đang dùng, kèm nhóm của nó. */
  record Target(String code, String category) {}

  /** Một dòng lịch sử, chưa gắn người thực hiện. */
  record Event(
      String action, Map<String, Object> before, Map<String, Object> after, String reason) {}

  /**
   * Kết quả một thao tác.
   *
   * @param sign trạng thái mới, {@code version} đã tăng (biển mới có {@code version = 0})
   * @param reviewed thao tác là một quyết định: ghi {@code reviewed_by}
   * @param weights các mã có {@code W_c} thay đổi
   * @param members đề xuất thành viên đổi trạng thái
   */
  record Outcome(
      SignState sign,
      boolean reviewed,
      Map<String, CodeWeight> weights,
      List<MemberUpdate> members,
      List<Event> events) {}

  static Outcome approve(
      SignState sign, int version, Target target, GeoPoint location, Instant now) {
    requireWritable(sign, version, EnumSet.of(SignStatus.PENDING), "duyệt");
    SignState edited = apply(sign, new SignChange(code(target), location, false, null), target);
    Settled settled = settle(decided(edited, SignStatus.APPROVED, now), true);
    return new Outcome(
        settled.sign(),
        true,
        settled.weights(),
        settled.members(),
        List.of(event("APPROVED", sign, settled.sign(), null)));
  }

  static Outcome reject(SignState sign, int version, String reason, Instant now) {
    requireWritable(sign, version, EnumSet.of(SignStatus.PENDING), "từ chối");
    SignState rejected =
        sign.toBuilder()
            .status(SignStatus.REJECTED)
            .reviewedAt(now)
            .suggestion(null)
            .version(sign.version() + 1)
            .build();
    String memberReason = truncate(reason, MEMBER_REASON_MAX);
    List<MemberUpdate> members =
        ClusteringEngine.memberUpdates(rejected).stream()
            .map(u -> new MemberUpdate(u.submissionId(), u.status(), memberReason))
            .toList();
    return new Outcome(
        withMembers(rejected, members),
        true,
        Map.of(),
        members,
        List.of(event("REJECTED", sign, rejected, reason)));
  }

  static Outcome keep(SignState sign, int version, SignChange change, Target target, Instant now) {
    requireWritable(sign, version, EnumSet.of(SignStatus.UNDER_REVIEW), "giữ");
    SignState edited = apply(sign, change, target);
    List<Event> events = new ArrayList<>();
    if (changed(sign, edited)) events.add(event("EDITED", sign, edited, null));
    Settled settled = settle(decided(edited, SignStatus.APPROVED, now), true);
    events.add(event("KEPT", edited, settled.sign(), null));
    return new Outcome(settled.sign(), true, settled.weights(), settled.members(), events);
  }

  static Outcome remove(SignState sign, int version, String reason, Instant now) {
    requireWritable(sign, version, EnumSet.of(SignStatus.APPROVED, SignStatus.UNDER_REVIEW), "gỡ");
    List<MemberUpdate> members =
        sign.members().stream()
            .filter(m -> m.status() == SubmissionStatus.GROUPED)
            .map(m -> new MemberUpdate(m.id(), SubmissionStatus.REJECTED, REASON_REMOVED))
            .toList();
    SignState removed =
        withMembers(
            sign.toBuilder()
                .status(SignStatus.REMOVED)
                .reviewedAt(now)
                .suggestion(null)
                .version(sign.version() + 1)
                .build(),
            members);
    return new Outcome(
        removed, true, Map.of(), members, List.of(event("REMOVED", sign, removed, reason)));
  }

  static Outcome edit(SignState sign, int version, SignChange change, Target target) {
    requireWritable(
        sign,
        version,
        EnumSet.of(SignStatus.PENDING, SignStatus.APPROVED, SignStatus.UNDER_REVIEW),
        "sửa");
    boolean codeChanged =
        change.signTypeCode() != null && !change.signTypeCode().equals(sign.signTypeCode());
    if (codeChanged && sign.status() == SignStatus.PENDING) {
      throw new ApiException(
          ErrorCode.BUSINESS_RULE,
          "Biển chờ duyệt chỉ sửa được vị trí và hướng; đổi mã khi duyệt biển");
    }
    SignState edited = apply(sign, change, target).toBuilder().version(sign.version() + 1).build();
    if (!changed(sign, edited)) {
      throw new ApiException(ErrorCode.BUSINESS_RULE, "Không có thay đổi nào so với biển hiện tại");
    }
    Settled settled =
        codeChanged
            ? settle(edited, sign.status() == SignStatus.APPROVED)
            : new Settled(edited, Map.of(), List.of());
    return new Outcome(
        settled.sign(),
        false,
        settled.weights(),
        settled.members(),
        List.of(event("EDITED", sign, settled.sign(), null)));
  }

  static Outcome create(
      String id, Target target, GeoPoint location, Integer headingDeg, Instant now) {
    SignState sign =
        SignState.builder()
            .id(id)
            .status(SignStatus.APPROVED)
            .signTypeCode(target.code())
            .categoryCode(target.category())
            .location(location)
            .headingDeg(headingDeg)
            .locationLocked(true)
            .moderatorBoost(MODERATOR_BOOST)
            .reviewedAt(now)
            .version(0)
            .build();
    SignState scored =
        sign.toBuilder().confidence(Scoring.score(sign, sign.signTypeCode())).build();
    return new Outcome(
        scored,
        true,
        Map.of(),
        List.of(),
        List.of(new Event("CREATED", null, snapshot(scored), null)));
  }

  /** {@code version} lệch hoặc trạng thái không cho phép thao tác: {@code 409}. */
  static void requireWritable(
      SignState sign, int version, Set<SignStatus> allowed, String operation) {
    if (sign.version() != version) {
      throw new ApiException(ErrorCode.SIGN_ALREADY_PROCESSED);
    }
    if (!allowed.contains(sign.status())) {
      throw new ApiException(
          ErrorCode.SIGN_ALREADY_PROCESSED,
          "Biển đang ở trạng thái " + sign.status() + ", không thể " + operation);
    }
  }

  /** Áp mã, vị trí, hướng. Có vị trí thì chốt vị trí ({@code location_locked}). */
  static SignState apply(SignState sign, SignChange change, Target target) {
    SignState.Builder b = sign.toBuilder();
    if (change.signTypeCode() != null) {
      b.signTypeCode(target.code()).categoryCode(target.category());
    }
    if (change.location() != null) b.location(change.location()).locationLocked(true);
    if (change.headingSet()) b.headingDeg(change.headingDeg());
    return b.build();
  }

  /** Biển được kiểm duyệt viên quyết định: {@code M = 2}, mốc rà soát mới. */
  private static SignState decided(SignState sign, SignStatus status, Instant now) {
    return sign.toBuilder()
        .status(status)
        .moderatorBoost(MODERATOR_BOOST)
        .reviewedAt(now)
        .denySinceReview(0)
        .suggestion(null)
        .version(sign.version() + 1)
        .build();
  }

  private record Settled(
      SignState sign, Map<String, CodeWeight> weights, List<MemberUpdate> members) {}

  /**
   * Chốt mã {@code c} = {@code signTypeCode} của biển. Mã khác có {@code W} về 0 (giữ {@code
   * submitter_count}) vì đề xuất của chúng bị từ chối, như đề xuất bị từ chối ở bước 4 của lát 5.
   * {@code W_c} bằng tổng {@code w} của đề xuất mang mã {@code c}, để chọn lại một mã đã bị loại
   * lấy lại bằng chứng của nó. Sau đó tính lại điểm.
   *
   * @param accept biển chuyển {@code APPROVED}: đề xuất cùng mã {@code ACCEPTED} theo {@link
   *     ClusteringEngine#memberUpdates}. {@code false} (sửa mã biển {@code UNDER_REVIEW}): chỉ từ
   *     chối đề xuất khác mã, đề xuất cùng mã chờ tới khi giữ hoặc gỡ
   */
  private static Settled settle(SignState sign, boolean accept) {
    String code = sign.signTypeCode();
    Map<String, CodeWeight> changed = new TreeMap<>();
    sign.codeWeights()
        .forEach((c, w) -> {
          if (!c.equals(code) && w.weight().signum() != 0) {
            changed.put(c, new CodeWeight(ZERO_WEIGHT, w.submitterCount()));
          }
        });
    List<MemberSubmission> own =
        sign.members().stream().filter(m -> m.signTypeCode().equals(code)).toList();
    if (!own.isEmpty()) {
      BigDecimal total =
          own.stream().map(MemberSubmission::weight).reduce(ZERO_WEIGHT, BigDecimal::add);
      CodeWeight current = sign.codeWeights().get(code);
      if (current == null || current.weight().compareTo(total) != 0) {
        int submitters =
            current != null
                ? current.submitterCount()
                : (int) own.stream().map(MemberSubmission::userId).distinct().count();
        changed.put(code, new CodeWeight(total, submitters));
      }
    }
    Map<String, CodeWeight> weights = new HashMap<>(sign.codeWeights());
    weights.putAll(changed);
    SignState weighted = sign.toBuilder().codeWeights(weights).build();

    List<MemberUpdate> members =
        accept
            ? ClusteringEngine.memberUpdates(weighted)
            : weighted.members().stream()
                .filter(m -> !m.signTypeCode().equals(code))
                .filter(m -> m.status() != SubmissionStatus.REJECTED)
                .map(m -> new MemberUpdate(m.id(), SubmissionStatus.REJECTED, REASON_OTHER_CODE))
                .toList();
    SignState updated = withMembers(weighted, members);
    SignState scored = updated.toBuilder().confidence(Scoring.score(updated, code)).build();
    return new Settled(scored, changed, members);
  }

  private static SignState withMembers(SignState sign, List<MemberUpdate> updates) {
    if (updates.isEmpty()) return sign;
    Map<String, SubmissionStatus> byId =
        updates.stream()
            .collect(Collectors.toMap(MemberUpdate::submissionId, MemberUpdate::status));
    List<MemberSubmission> members =
        sign.members().stream()
            .map(m -> {
              SubmissionStatus status = byId.get(m.id());
              return status == null
                  ? m
                  : new MemberSubmission(
                      m.id(),
                      m.userId(),
                      m.signTypeCode(),
                      m.weight(),
                      m.location(),
                      m.receivedAt(),
                      status);
            })
            .toList();
    return sign.toBuilder().members(members).build();
  }

  /** Các trường ghi lịch sử (FR-23): trạng thái, mã, vị trí, hướng, điểm. */
  static Map<String, Object> snapshot(SignState sign) {
    Map<String, Object> values = new LinkedHashMap<>();
    values.put("status", sign.status().name());
    values.put("signTypeCode", sign.signTypeCode());
    values.put("categoryCode", sign.categoryCode());
    values.put("location", latLng(sign.location()));
    values.put("headingDeg", sign.headingDeg());
    values.put("locationLocked", sign.locationLocked());
    values.put("confidence", sign.confidence());
    return values;
  }

  static boolean changed(SignState before, SignState after) {
    return !diff(before, after, Map.Entry::getKey).isEmpty();
  }

  /** Sự kiện với {@code before}, {@code after} chỉ gồm trường thay đổi. */
  static Event event(String action, SignState before, SignState after, String reason) {
    Map<String, Object> old = snapshot(before);
    return new Event(
        action,
        diff(before, after, e -> old.get(e.getKey())),
        diff(before, after, Map.Entry::getValue),
        reason);
  }

  private static Map<String, Object> diff(
      SignState before, SignState after, Function<Map.Entry<String, Object>, Object> pick) {
    Map<String, Object> old = snapshot(before);
    Map<String, Object> result = new LinkedHashMap<>();
    for (Map.Entry<String, Object> e : snapshot(after).entrySet()) {
      if (!same(old.get(e.getKey()), e.getValue())) result.put(e.getKey(), pick.apply(e));
    }
    return result;
  }

  private static boolean same(Object a, Object b) {
    if (a instanceof BigDecimal x && b instanceof BigDecimal y) return x.compareTo(y) == 0;
    return Objects.equals(a, b);
  }

  private static Map<String, Object> latLng(GeoPoint point) {
    Map<String, Object> values = new LinkedHashMap<>();
    values.put("lat", point.lat());
    values.put("lng", point.lng());
    return values;
  }

  private static String code(Target target) {
    return target == null ? null : target.code();
  }

  static String truncate(String value, int max) {
    if (value.length() <= max) return value;
    int end = max;
    if (Character.isHighSurrogate(value.charAt(end - 1))) end--;
    return value.substring(0, end);
  }
}
