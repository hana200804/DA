package vn.signa.sign.internal.feedback;

import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import vn.signa.audit.api.AuditEntityType;
import vn.signa.audit.api.AuditEntry;
import vn.signa.audit.api.AuditRecorder;
import vn.signa.shared.error.ApiException;
import vn.signa.shared.error.ErrorCode;
import vn.signa.sign.clustering.ClusteringConfig;
import vn.signa.sign.clustering.ClusteringEngine;
import vn.signa.sign.clustering.RatingChange;
import vn.signa.sign.clustering.RatingValue;

@Service
public class RatingService {

  private final RatingRepository ratings;
  private final AuditRecorder audit;
  private final Clock clock;
  private final ClusteringEngine engine =
      new ClusteringEngine(ClusteringConfig.DEFAULTS, () -> UUID.randomUUID().toString());

  RatingService(RatingRepository ratings, AuditRecorder audit, Clock clock) {
    this.ratings = ratings;
    this.audit = audit;
    this.clock = clock;
  }

  @Transactional(isolation = Isolation.READ_COMMITTED)
  public void rate(String signId, String userId, RatingValue value) {
    var sign = ratings.lockPublished(signId).orElseThrow(ApiException::notFound);
    if (ratings.hasSubmission(signId, userId)) {
      throw new ApiException(ErrorCode.BUSINESS_RULE, "Không thể đánh giá biển mình đã đề xuất");
    }
    var now = clock.instant().truncatedTo(ChronoUnit.MILLIS);
    var previous = ratings.previous(signId, userId).orElse(null);
    // Lõi gom cụm nhận số đếm trước khi ghi đè và tự áp dụng chênh lệch của đánh giá này.
    var current =
        sign.toBuilder()
            .codeWeights(ratings.codeWeights(signId))
            .denySinceReview(ratings.countDeniesSinceReview(signId, sign.reviewedAt()))
            .build();
    ratings.upsert(signId, userId, value, now);
    var change =
        new RatingChange(
            previous == null ? null : previous.value(),
            previous == null ? null : previous.updatedAt(),
            value,
            now);
    var result = engine.applyRating(current, change);
    ratings.updateSign(result.sign(), now);
    result
        .events()
        .forEach(event ->
            audit.record(
                AuditEntry.bySystem(
                    AuditEntityType.SIGN,
                    signId,
                    event.type().name(),
                    event.before(),
                    event.after(),
                    event.reason())));
  }
}
