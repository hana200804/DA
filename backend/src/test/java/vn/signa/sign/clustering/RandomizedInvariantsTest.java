package vn.signa.sign.clustering;

import static org.assertj.core.api.Assertions.assertThat;
import static vn.signa.sign.clustering.Fixtures.ORIGIN;
import static vn.signa.sign.clustering.Fixtures.T0;
import static vn.signa.sign.clustering.Fixtures.offset;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Lặp ngẫu nhiên có seed: đề xuất, đánh giá và quyết định của kiểm duyệt viên xen kẽ. */
class RandomizedInvariantsTest {

  private final Map<String, RatingValue> ratings = new HashMap<>();

  private static final long SEED = 20261123L;
  private static final List<String> CODES = List.of("P.123a", "P.123b", "P.127");

  @Test
  @DisplayName("Điểm luôn trong [0; 1], trọng số không âm, W_c bằng tổng w của thành viên")
  void invariantsHold() {
    Random random = new Random(SEED);
    int checked = 0;
    for (int run = 0; run < 300; run++) {
      ClusteringConfig config = ClusteringConfig.DEFAULTS.withAutoApprove(random.nextBoolean());
      World world = new World(Fixtures.engine(config));
      Instant now = T0;
      for (int step = 0; step < 40; step++) {
        now = now.plus(Duration.ofMinutes(random.nextInt(600)));
        int action = random.nextInt(10);
        if (action < 7 || world.signs.isEmpty()) {
          ClusteringOutcome outcome = world.submit(randomSubmission(random, step, now), now);
          assertThat(outcome.submissionWeight().signum()).isGreaterThanOrEqualTo(0);
          assertThat(outcome.submissionWeight().compareTo(BigDecimal.ONE)).isLessThanOrEqualTo(0);
        } else if (action < 9) {
          SignState sign = pick(random, world);
          if (sign.status().isPublished()) {
            String user = "r" + random.nextInt(6);
            String key = sign.id() + "/" + user;
            RatingValue value = random.nextBoolean() ? RatingValue.DENY : RatingValue.CONFIRM;
            RatingValue previous = ratings.get(key);
            RatingChange change =
                new RatingChange(
                    previous, previous == null ? null : now.minusSeconds(1), value, now);
            world.signs.put(sign.id(), world.engine.applyRating(sign, change).sign());
            ratings.put(key, value);
          }
        } else {
          SignState sign = pick(random, world);
          if (sign.status() == SignStatus.PENDING) {
            world.signs.put(sign.id(), moderate(random, sign, now));
          }
        }
        for (SignState sign : world.signs.values()) {
          assertInvariants(sign);
          checked++;
        }
      }
      ratings.clear();
    }
    assertThat(checked).isGreaterThan(10_000);
  }

  private static SubmissionInput randomSubmission(Random random, int step, Instant now) {
    GeoPoint at = offset(ORIGIN, random.nextDouble() * 60 - 30, random.nextDouble() * 60 - 30);
    Integer heading = random.nextInt(4) == 0 ? null : random.nextInt(360);
    EnumSet<SubmissionFlag> flags = EnumSet.noneOf(SubmissionFlag.class);
    if (random.nextInt(5) == 0) flags.add(SubmissionFlag.SPLIT_VOTE);
    if (random.nextInt(6) == 0) flags.add(SubmissionFlag.USER_EDITED);
    BigDecimal p = BigDecimal.valueOf(random.nextInt(10_001), 4);
    return new SubmissionInput(
        "s" + step + "-" + random.nextInt(1_000_000),
        "u" + random.nextInt(5),
        CODES.get(random.nextInt(CODES.size())),
        "PROHIBITORY",
        p,
        at,
        heading,
        flags,
        now);
  }

  private static SignState pick(Random random, World world) {
    List<SignState> all = new ArrayList<>(world.signs.values());
    return all.get(random.nextInt(all.size()));
  }

  /** Kiểm duyệt viên duyệt (M = 2) hoặc từ chối, như lát 6 sẽ làm. */
  private static SignState moderate(Random random, SignState sign, Instant now) {
    boolean approve = random.nextBoolean();
    SignState decided =
        sign.toBuilder()
            .status(approve ? SignStatus.APPROVED : SignStatus.REJECTED)
            .moderatorBoost(approve ? new BigDecimal("2") : sign.moderatorBoost())
            .suggestion(null)
            .denySinceReview(0)
            .reviewedAt(now)
            .build();
    if (approve) {
      decided =
          decided.toBuilder().confidence(Scoring.score(decided, decided.signTypeCode())).build();
    }
    Map<String, SubmissionStatus> updates =
        ClusteringEngine.memberUpdates(decided).stream()
            .collect(Collectors.toMap(MemberUpdate::submissionId, MemberUpdate::status));
    return decided.toBuilder()
        .members(
            decided.members().stream()
                .map(m -> updates.containsKey(m.id()) ? m.withStatus(updates.get(m.id())) : m)
                .toList())
        .build();
  }

  private static void assertInvariants(SignState sign) {
    assertThat(sign.confidence()).isBetween(BigDecimal.ZERO, BigDecimal.ONE);
    assertThat(sign.confidence().scale()).isLessThanOrEqualTo(Scoring.SCALE);
    for (var entry : sign.codeWeights().entrySet()) {
      BigDecimal sum =
          sign.members().stream()
              .filter(m -> m.signTypeCode().equals(entry.getKey()))
              .map(MemberSubmission::weight)
              .reduce(BigDecimal.ZERO, BigDecimal::add);
      assertThat(entry.getValue().weight().signum()).isGreaterThanOrEqualTo(0);
      assertThat(entry.getValue().weight()).isEqualByComparingTo(sum);
      long users =
          sign.members().stream()
              .filter(m -> m.signTypeCode().equals(entry.getKey()))
              .map(MemberSubmission::userId)
              .distinct()
              .count();
      assertThat(entry.getValue().submitterCount()).isEqualTo((int) users);
    }
    assertThat(sign.members()).allMatch(m -> m.weight().signum() >= 0);
    assertThat(sign.distinctSubmitters())
        .isEqualTo((int) sign.members().stream().map(MemberSubmission::userId).distinct().count());
    // Không có hai đề xuất có trọng số của cùng người cách nhau dưới 12 giờ.
    List<MemberSubmission> weighted =
        sign.members().stream().filter(m -> m.weight().signum() > 0).toList();
    for (int i = 0; i < weighted.size(); i++) {
      for (int j = i + 1; j < weighted.size(); j++) {
        MemberSubmission a = weighted.get(i);
        MemberSubmission b = weighted.get(j);
        if (a.userId().equals(b.userId())) {
          assertThat(Duration.between(a.receivedAt(), b.receivedAt()).abs())
              .isGreaterThanOrEqualTo(Duration.ofHours(12));
        }
      }
    }
    if (sign.status() == SignStatus.PENDING) {
      assertThat(sign.suggestion()).isNotNull();
    }
  }
}
