package vn.signa.sign.internal;

import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import vn.signa.audit.api.AuditEntityType;
import vn.signa.audit.api.AuditEntry;
import vn.signa.audit.api.AuditRecorder;
import vn.signa.catalog.api.SignCatalog;
import vn.signa.sign.clustering.ClusteringConfig;
import vn.signa.sign.clustering.ClusteringEngine;
import vn.signa.sign.clustering.SignState;
import vn.signa.sign.clustering.SubmissionStatus;

@Service
class ClusteringProcessor {

  private final SubmissionRepository submissions;
  private final SignRepository signs;
  private final SignCodeWeightRepository weights;
  private final SignCatalog catalog;
  private final AuditRecorder audit;
  private final Clock clock;
  private final ClusteringEngine engine;

  ClusteringProcessor(
      SubmissionRepository submissions,
      SignRepository signs,
      SignCodeWeightRepository weights,
      SignCatalog catalog,
      AuditRecorder audit,
      Clock clock,
      ClusteringProperties properties) {
    this.submissions = submissions;
    this.signs = signs;
    this.weights = weights;
    this.catalog = catalog;
    this.audit = audit;
    this.clock = clock;
    this.engine =
        new ClusteringEngine(
            ClusteringConfig.DEFAULTS.withAutoApprove(properties.autoApprove()),
            () -> UUID.randomUUID().toString());
  }

  @Transactional(isolation = Isolation.READ_COMMITTED)
  public void process(String id) {
    var submission = submissions.lock(id).orElse(null);
    if (submission == null || submission.status() != SubmissionStatus.RECEIVED) return;

    String category = catalog.categoryOf(submission.signTypeCode()).orElseThrow();
    var input = submission.input(category);
    var now = clock.instant().truncatedTo(ChronoUnit.MILLIS);
    var config = engine.config();
    List<SignState> candidates =
        signs.candidates(
            input, config.radiusMeters(), input.receivedAt().minus(config.rejectedWindow()));
    var result = engine.process(input, withEvidence(candidates), now);
    SignState sign = result.sign();

    if (result.signCreated()) {
      signs.insert(sign, submission.cropKey(), now);
    } else if (result.signChanged()) {
      signs.update(sign, submissions.bestCrop(sign.id(), id, sign.signTypeCode()), now);
    }
    if (result.signChanged()) weights.upsert(sign);
    submissions.finish(id, result);
    result.memberUpdates().forEach(submissions::updateMember);
    result
        .events()
        .forEach(event ->
            audit.record(
                AuditEntry.bySystem(
                    AuditEntityType.SIGN,
                    sign.id(),
                    event.type().name(),
                    event.before(),
                    event.after(),
                    event.reason())));
  }

  private List<SignState> withEvidence(List<SignState> candidates) {
    if (candidates.isEmpty()) return candidates;
    List<String> ids = candidates.stream().map(SignState::id).toList();
    var bySign = weights.forSigns(ids);
    var membersBySign = submissions.forSigns(ids);
    return candidates.stream()
        .map(sign ->
            sign.toBuilder()
                .codeWeights(bySign.getOrDefault(sign.id(), Map.of()))
                .members(membersBySign.getOrDefault(sign.id(), List.of()))
                .build())
        .toList();
  }
}
