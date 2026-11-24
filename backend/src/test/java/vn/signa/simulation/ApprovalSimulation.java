package vn.signa.simulation;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;
import vn.signa.sign.clustering.ClusteringConfig;
import vn.signa.sign.clustering.ClusteringEngine;
import vn.signa.sign.clustering.Geo;
import vn.signa.sign.clustering.GeoPoint;
import vn.signa.sign.clustering.SignState;
import vn.signa.sign.clustering.SignStatus;
import vn.signa.sign.clustering.SubmissionInput;
import vn.signa.sign.clustering.Suggestion;

/** Mô phỏng lặp lại được theo docs/08 §6, chạy qua src/test/shell/approval-simulation.sh. */
public final class ApprovalSimulation {

  private static final long SEED = 20261124L;
  private static final Instant START = Instant.parse("2026-11-24T00:00:00Z");
  private static final int PASSES = 3;
  private static final int SIGNS_PER_ROUTE = 30;
  private static final int ATTACKS_PER_SIGN = 5;
  private static final double MATCH_DISTANCE = 15;
  private static final BigDecimal CONFIDENCE = new BigDecimal("0.90");

  private static final List<Scenario> SCENARIOS =
      List.of(
          new Scenario("Bình thường", 0, 0, false),
          new Scenario("Nhiễu mô hình 10%", 0.10, 0, false),
          new Scenario("Nhiễu mô hình 20%", 0.20, 0, false),
          new Scenario("Nhiễu GPS σ=5 m", 0, 5, false),
          new Scenario("Nhiễu GPS σ=10 m", 0, 10, false),
          new Scenario("Nhiễu GPS σ=15 m", 0, 15, false),
          new Scenario("Người dùng phá 20%", 0, 0, true));

  private record Scenario(String name, double wrongCodeShare, int gpsSigma, boolean disruption) {}

  private record ActualSign(int id, GeoPoint point, String group, String code, String alternate) {}

  private record Metrics(
      int decisions, int approved, int correct, int suggested, int correctSuggestions) {}

  private ApprovalSimulation() {}

  public static void main(String[] args) {
    List<ActualSign> truth = syntheticRoutes();
    System.out.printf("Seed %d | %d biển chuẩn | %d lượt/tuyến%n", SEED, truth.size(), PASSES);
    System.out.println(
        "Kịch bản | Đề xuất | Quyết định từng đề xuất | Precision từng đề xuất"
            + " | Quyết định gom cụm | Giảm quyết định | Precision gom cụm | Recall gom cụm"
            + " | Gợi ý đúng | Tự duyệt (đúng/tổng, precision)");
    Metrics normal = null;
    for (Scenario scenario : SCENARIOS) {
      List<SubmissionInput> proposals = proposals(truth, scenario);
      Metrics oneByOne = oneByOne(truth, proposals);
      Metrics assisted = assisted(truth, proposals);
      Metrics automatic = automatic(truth, proposals);
      print(scenario, truth.size(), proposals.size(), oneByOne, assisted, automatic);
      if (normal == null) {
        normal = assisted;
      }
      if (scenario.disruption()) {
        System.out.printf(
            Locale.ROOT,
            "Mức giảm precision khi bị phá: %.2f điểm phần trăm%n",
            100
                * ((double) normal.correct() / normal.approved()
                    - (double) assisted.correct() / assisted.approved()));
      }
    }
  }

  private static List<ActualSign> syntheticRoutes() {
    return IntStream.range(0, 2 * SIGNS_PER_ROUTE)
        .mapToObj(id -> {
          int route = id / SIGNS_PER_ROUTE;
          GeoPoint origin = new GeoPoint(21.027 + route * 0.007, 105.831);
          GeoPoint point = shift(origin, 0, (id % SIGNS_PER_ROUTE) * 55.0);
          boolean mandatory = id % 3 == 0;
          String group = mandatory ? "MANDATORY" : "PROHIBITORY";
          String[] codes =
              mandatory ? new String[] {"R.301a", "R.301b"} : new String[] {"P.102", "P.104"};
          return new ActualSign(id, point, group, codes[id % 2], codes[(id + 1) % 2]);
        })
        .toList();
  }

  private static List<SubmissionInput> proposals(List<ActualSign> truth, Scenario scenario) {
    Set<Integer> wrong = choose(truth.size() * PASSES, scenario.wrongCodeShare());
    Set<Integer> attacked = scenario.disruption() ? choose(truth.size(), 0.20) : Set.of();
    Random gps = new Random(SEED);
    List<SubmissionInput> result = new ArrayList<>();
    for (int pass = 0; pass < PASSES; pass++) {
      for (ActualSign sign : truth) {
        int index = pass * truth.size() + sign.id();
        double sigma = scenario.gpsSigma();
        GeoPoint point =
            shift(sign.point(), gps.nextGaussian() * sigma, gps.nextGaussian() * sigma);
        String code = wrong.contains(index) ? sign.alternate() : sign.code();
        result.add(proposal(index, "luot-" + pass, sign, code, point, result.size()));
        // Gửi liên tiếp trong cửa sổ chống lặp, cùng một tài khoản và vị trí.
        if (pass == 0 && attacked.contains(sign.id())) {
          for (int i = 0; i < ATTACKS_PER_SIGN; i++) {
            int duplicate = truth.size() * PASSES + sign.id() * ATTACKS_PER_SIGN + i;
            result.add(
                proposal(
                    duplicate,
                    "tai-khoan-pha",
                    sign,
                    sign.alternate(),
                    sign.point(),
                    result.size()));
          }
        }
      }
    }
    return List.copyOf(result);
  }

  private static Set<Integer> choose(int size, double share) {
    var indices = new ArrayList<>(IntStream.range(0, size).boxed().toList());
    Collections.shuffle(indices, new Random(SEED));
    return Set.copyOf(indices.subList(0, (int) Math.round(size * share)));
  }

  private static SubmissionInput proposal(
      int index, String user, ActualSign sign, String code, GeoPoint point, int position) {
    return new SubmissionInput(
        "de-xuat-" + index,
        user,
        code,
        sign.group(),
        CONFIDENCE,
        point,
        90,
        Set.of(),
        START.plus(position, ChronoUnit.SECONDS));
  }

  private static GeoPoint shift(GeoPoint point, double north, double east) {
    double lat = point.lat() + Math.toDegrees(north / Geo.EARTH_RADIUS_M);
    double lng =
        point.lng()
            + Math.toDegrees(east / (Geo.EARTH_RADIUS_M * Math.cos(Math.toRadians(point.lat()))));
    return new GeoPoint(lat, lng);
  }

  private static Metrics oneByOne(List<ActualSign> truth, List<SubmissionInput> proposals) {
    Set<Integer> approved = new HashSet<>();
    for (SubmissionInput proposal : proposals) {
      match(truth, Set.of(proposal.signTypeCode()), proposal.location(), approved);
    }
    return new Metrics(proposals.size(), approved.size(), approved.size(), 0, 0);
  }

  private static Metrics assisted(List<ActualSign> truth, List<SubmissionInput> proposals) {
    List<SignState> clusters = cluster(proposals, false);
    Set<Integer> correct = new HashSet<>();
    int approved = 0;
    int suggested = 0;
    int correctSuggestions = 0;
    for (SignState sign : clusters) {
      if (sign.suggestion() == Suggestion.APPROVE) {
        approved++;
        suggested++;
        if (match(truth, Set.of(sign.signTypeCode()), sign.location(), correct)) {
          correctSuggestions++;
        }
      } else if (match(truth, sign.codeWeights().keySet(), sign.location(), correct)) {
        approved++;
      }
    }
    return new Metrics(clusters.size(), approved, correct.size(), suggested, correctSuggestions);
  }

  private static Metrics automatic(List<ActualSign> truth, List<SubmissionInput> proposals) {
    List<SignState> approved =
        cluster(proposals, true).stream()
            .filter(sign -> sign.status() == SignStatus.APPROVED)
            .toList();
    Set<Integer> correct = new HashSet<>();
    for (SignState sign : approved) {
      match(truth, Set.of(sign.signTypeCode()), sign.location(), correct);
    }
    return new Metrics(0, approved.size(), correct.size(), 0, 0);
  }

  private static List<SignState> cluster(List<SubmissionInput> proposals, boolean autoApprove) {
    AtomicInteger ids = new AtomicInteger();
    ClusteringEngine engine =
        new ClusteringEngine(
            ClusteringConfig.DEFAULTS.withAutoApprove(autoApprove),
            () -> "cum-" + ids.incrementAndGet());
    var signs = new LinkedHashMap<String, SignState>();
    for (SubmissionInput proposal : proposals) {
      var outcome = engine.process(proposal, List.copyOf(signs.values()), proposal.receivedAt());
      if (outcome.signChanged()) {
        signs.put(outcome.sign().id(), outcome.sign());
      }
    }
    return List.copyOf(signs.values());
  }

  private static boolean match(
      List<ActualSign> truth, Set<String> codes, GeoPoint point, Set<Integer> used) {
    return truth.stream()
        .filter(sign -> codes.contains(sign.code()) && !used.contains(sign.id()))
        .filter(sign -> Geo.distanceMeters(sign.point(), point) <= MATCH_DISTANCE)
        .min(Comparator.comparingDouble(sign -> Geo.distanceMeters(sign.point(), point)))
        .map(sign -> used.add(sign.id()))
        .orElse(false);
  }

  private static void print(
      Scenario scenario,
      int total,
      int proposals,
      Metrics baseline,
      Metrics assisted,
      Metrics automatic) {
    System.out.printf(
        "%s | %d | %d | %s | %d | %s | %s | %s | %s | %d/%d, %s%n",
        scenario.name(),
        proposals,
        baseline.decisions(),
        percent(baseline.correct(), baseline.approved()),
        assisted.decisions(),
        percent(baseline.decisions() - assisted.decisions(), baseline.decisions()),
        percent(assisted.correct(), assisted.approved()),
        percent(assisted.correct(), total),
        percent(assisted.correctSuggestions(), assisted.suggested()),
        automatic.correct(),
        automatic.approved(),
        percent(automatic.correct(), automatic.approved()));
  }

  private static String percent(int count, int total) {
    return total == 0 ? "—" : String.format(Locale.ROOT, "%.2f%%", 100.0 * count / total);
  }
}
