package vn.signa.sign.clustering;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/** Dữ liệu dựng sẵn cho test gom cụm. */
final class Fixtures {

  static final GeoPoint ORIGIN = new GeoPoint(21.0285, 105.8048);
  static final Instant T0 = Instant.parse("2026-11-01T08:00:00Z");
  static final String CATEGORY = "PROHIBITORY";
  static final String CODE = "P.123a";
  static final String OTHER_CODE = "P.123b";

  private static final double METERS_PER_DEGREE = Geo.EARTH_RADIUS_M * Math.PI / 180;

  private Fixtures() {}

  /** Điểm cách {@code from} {@code north} mét về bắc và {@code east} mét về đông. */
  static GeoPoint offset(GeoPoint from, double north, double east) {
    double lat = from.lat() + north / METERS_PER_DEGREE;
    double lng = from.lng() + east / (METERS_PER_DEGREE * Math.cos(Math.toRadians(from.lat())));
    return new GeoPoint(lat, lng);
  }

  static GeoPoint north(double meters) {
    return offset(ORIGIN, meters, 0);
  }

  static ClusteringEngine engine() {
    return engine(ClusteringConfig.DEFAULTS);
  }

  static ClusteringEngine engine(ClusteringConfig config) {
    AtomicInteger next = new AtomicInteger(1);
    return new ClusteringEngine(config, () -> "sign-" + next.getAndIncrement());
  }

  static SubmissionBuilder sub(String id, String user) {
    return new SubmissionBuilder(id, user);
  }

  static Instant hours(double h) {
    return T0.plus(Duration.ofMillis((long) (h * 3_600_000)));
  }

  static SignState.Builder approvedSign(String id, String code, GeoPoint at, Integer heading) {
    return SignState.builder()
        .id(id)
        .status(SignStatus.APPROVED)
        .signTypeCode(code)
        .categoryCode(CATEGORY)
        .location(at)
        .headingDeg(heading)
        .moderatorBoost(new BigDecimal("2"))
        .reviewedAt(T0.minus(Duration.ofDays(1)));
  }

  static SignState.Builder pendingSign(String id, String code, GeoPoint at, Integer heading) {
    return SignState.builder()
        .id(id)
        .status(SignStatus.PENDING)
        .signTypeCode(code)
        .categoryCode(CATEGORY)
        .location(at)
        .headingDeg(heading);
  }

  static SignState.Builder rejectedSign(
      String id, String code, GeoPoint at, Integer heading, Instant reviewedAt) {
    return SignState.builder()
        .id(id)
        .status(SignStatus.REJECTED)
        .signTypeCode(code)
        .categoryCode(CATEGORY)
        .location(at)
        .headingDeg(heading)
        .reviewedAt(reviewedAt);
  }

  static MemberSubmission member(
      String id, String user, String code, String weight, GeoPoint at, Instant receivedAt) {
    return new MemberSubmission(
        id, user, code, new BigDecimal(weight), at, receivedAt, SubmissionStatus.GROUPED);
  }

  static final class SubmissionBuilder {
    private final String id;
    private final String user;
    private String code = CODE;
    private String category = CATEGORY;
    private BigDecimal p = new BigDecimal("0.9");
    private GeoPoint at = ORIGIN;
    private Integer heading = 90;
    private Set<SubmissionFlag> flags = EnumSet.noneOf(SubmissionFlag.class);
    private Instant receivedAt = T0;

    private SubmissionBuilder(String id, String user) {
      this.id = id;
      this.user = user;
    }

    SubmissionBuilder code(String v) {
      code = v;
      return this;
    }

    SubmissionBuilder category(String v) {
      category = v;
      return this;
    }

    SubmissionBuilder p(String v) {
      p = new BigDecimal(v);
      return this;
    }

    SubmissionBuilder at(GeoPoint v) {
      at = v;
      return this;
    }

    SubmissionBuilder heading(Integer v) {
      heading = v;
      return this;
    }

    SubmissionBuilder flags(SubmissionFlag... v) {
      flags = v.length == 0 ? EnumSet.noneOf(SubmissionFlag.class) : EnumSet.copyOf(List.of(v));
      return this;
    }

    SubmissionBuilder at(Instant v) {
      receivedAt = v;
      return this;
    }

    SubmissionInput build() {
      return new SubmissionInput(id, user, code, category, p, at, heading, flags, receivedAt);
    }
  }
}
