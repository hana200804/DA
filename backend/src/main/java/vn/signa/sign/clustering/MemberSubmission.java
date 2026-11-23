package vn.signa.sign.clustering;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;

/** Đề xuất đã gán vào một biển, đủ để tính chống lặp, số người và vị trí. */
public record MemberSubmission(
    String id,
    String userId,
    String signTypeCode,
    BigDecimal weight,
    GeoPoint location,
    Instant receivedAt,
    SubmissionStatus status) {

  public MemberSubmission {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(userId, "userId");
    Objects.requireNonNull(signTypeCode, "signTypeCode");
    Objects.requireNonNull(weight, "weight");
    Objects.requireNonNull(location, "location");
    Objects.requireNonNull(receivedAt, "receivedAt");
    Objects.requireNonNull(status, "status");
    if (weight.signum() < 0) {
      throw new IllegalArgumentException("weight âm: " + weight);
    }
  }

  MemberSubmission withStatus(SubmissionStatus newStatus) {
    return new MemberSubmission(id, userId, signTypeCode, weight, location, receivedAt, newStatus);
  }
}
