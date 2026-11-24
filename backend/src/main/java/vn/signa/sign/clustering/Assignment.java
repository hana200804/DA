package vn.signa.sign.clustering;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;

/** Luật gán đề xuất vào biển, 5 bước của {@code docs/04 §6}, xét đúng thứ tự. */
public final class Assignment {

  /** Loại quyết định. */
  public enum Kind {
    /** Bước 2: xác nhận biển {@code APPROVED}/{@code UNDER_REVIEW} cùng mã. */
    CONFIRM,
    /** Bước 3: gán vào biển {@code PENDING} gần nhất. */
    JOIN_PENDING,
    /** Bước 4: gán vào biển cùng mã đã bị từ chối trong 30 ngày và từ chối đề xuất. */
    REJECT,
    /** Bước 5: tạo biển {@code PENDING} mới. */
    CREATE
  }

  /**
   * Quyết định gán.
   *
   * @param target biển được gán; {@code null} khi {@link Kind#CREATE}
   * @param related biển đã duyệt khác mã gần nhất khi {@link Kind#CREATE}, nếu có
   */
  public record Decision(Kind kind, SignState target, SignState related) {}

  private record Match(SignState sign, double distance) {}

  private static final Comparator<Match> NEAREST =
      Comparator.comparingDouble(Match::distance).thenComparing(m -> m.sign().id());

  private Assignment() {}

  /**
   * Chọn biển cho đề xuất.
   *
   * @param candidates biển đã lọc thô quanh đề xuất; luật này tự lọc chính xác
   */
  public static Decision decide(
      SubmissionInput submission, List<SignState> candidates, ClusteringConfig config) {
    List<Match> matches =
        candidates.stream()
            .filter(sign -> eligible(submission, sign, config))
            .map(
                sign -> new Match(sign, Geo.distanceMeters(submission.location(), sign.location())))
            .filter(m -> m.distance() <= config.radiusMeters())
            .sorted(NEAREST)
            .toList();
    String code = submission.signTypeCode();

    Optional<SignState> published =
        nearest(matches, s -> s.status().isPublished() && s.signTypeCode().equals(code));
    if (published.isPresent()) {
      return new Decision(Kind.CONFIRM, published.get(), null);
    }
    Optional<SignState> pending = nearest(matches, s -> s.status() == SignStatus.PENDING);
    if (pending.isPresent()) {
      return new Decision(Kind.JOIN_PENDING, pending.get(), null);
    }
    Optional<SignState> rejected =
        nearest(matches, s -> s.status() == SignStatus.REJECTED && s.signTypeCode().equals(code));
    if (rejected.isPresent()) {
      return new Decision(Kind.REJECT, rejected.get(), null);
    }
    SignState related = nearest(matches, s -> s.status().isPublished()).orElse(null);
    return new Decision(Kind.CREATE, null, related);
  }

  /** Bước 1 trừ khoảng cách: cùng nhóm, hướng khớp, bỏ {@code REMOVED} và từ chối quá hạn. */
  static boolean eligible(SubmissionInput submission, SignState sign, ClusteringConfig config) {
    if (!sign.categoryCode().equals(submission.categoryCode())) {
      return false;
    }
    if (sign.status() == SignStatus.REMOVED) {
      return false;
    }
    if (sign.status() == SignStatus.REJECTED
        && submission.receivedAt().isAfter(sign.reviewedAt().plus(config.rejectedWindow()))) {
      return false;
    }
    return Geo.headingsCompatible(
        submission.headingDeg(), sign.headingDeg(), config.maxHeadingDiffDeg());
  }

  private static Optional<SignState> nearest(List<Match> sorted, Predicate<SignState> filter) {
    return sorted.stream().map(Match::sign).filter(filter).findFirst();
  }
}
