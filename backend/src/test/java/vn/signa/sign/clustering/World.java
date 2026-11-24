package vn.signa.sign.clustering;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Mô phỏng repository: giữ các biển và áp kết quả gom cụm như worker sẽ làm. */
final class World {

  final ClusteringEngine engine;
  final Map<String, SignState> signs = new LinkedHashMap<>();
  final List<ClusteringOutcome> outcomes = new ArrayList<>();

  World(ClusteringEngine engine) {
    this.engine = engine;
  }

  World with(SignState sign) {
    signs.put(sign.id(), sign);
    return this;
  }

  ClusteringOutcome submit(SubmissionInput submission) {
    return submit(submission, submission.receivedAt());
  }

  ClusteringOutcome submit(SubmissionInput submission, Instant now) {
    ClusteringOutcome outcome = engine.process(submission, List.copyOf(signs.values()), now);
    if (outcome.signChanged()) {
      signs.put(outcome.sign().id(), outcome.sign());
    }
    outcomes.add(outcome);
    return outcome;
  }

  SignState sign(String id) {
    return signs.get(id);
  }
}
