package vn.signa.sign.internal.feedback;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static vn.signa.sign.clustering.RatingValue.CONFIRM;
import static vn.signa.sign.clustering.RatingValue.DENY;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import vn.signa.audit.api.ActorType;
import vn.signa.audit.api.AuditEntityType;
import vn.signa.audit.api.AuditEntry;
import vn.signa.audit.api.AuditRecorder;
import vn.signa.shared.error.ApiException;
import vn.signa.shared.error.ErrorCode;
import vn.signa.sign.clustering.CodeWeight;
import vn.signa.sign.clustering.GeoPoint;
import vn.signa.sign.clustering.SignState;
import vn.signa.sign.clustering.SignStatus;
import vn.signa.sign.internal.feedback.RatingRepository.PreviousRating;

class RatingServiceTest {

  private static final String SIGN = "s-1";
  private static final Instant NOW = Instant.parse("2026-11-24T02:20:00Z");
  private static final Instant REVIEWED = NOW.minusSeconds(60);
  private final RatingRepository repository = mock(RatingRepository.class);
  private final AuditRecorder audit = mock(AuditRecorder.class);
  private final RatingService service =
      new RatingService(repository, audit, Clock.fixed(NOW, ZoneOffset.UTC));
  private final SignState sign =
      SignState.builder()
          .id(SIGN)
          .signTypeCode("P.102")
          .categoryCode("PROHIBITORY")
          .location(new GeoPoint(21, 105))
          .status(SignStatus.APPROVED)
          .moderatorBoost(new BigDecimal("2"))
          .reviewedAt(REVIEWED)
          .version(5)
          .build();

  @BeforeEach
  void evidence() {
    when(repository.codeWeights(SIGN))
        .thenReturn(Map.of("P.102", new CodeWeight(new BigDecimal("4"), 4)));
  }

  @Test
  void utB08ThirdDistinctDenialMarksTheSignForReviewAndRecordsHistory() {
    when(repository.lockPublished(SIGN))
        .thenReturn(
            Optional.of(sign),
            Optional.of(sign.toBuilder().denyCount(1).version(6).build()),
            Optional.of(sign.toBuilder().denyCount(2).version(7).build()));
    when(repository.countDeniesSinceReview(SIGN, REVIEWED)).thenReturn(0, 1, 2);

    service.rate(SIGN, "u1", DENY);
    service.rate(SIGN, "u2", DENY);
    verifyNoInteractions(audit);
    service.rate(SIGN, "u3", DENY);

    verify(repository).upsert(SIGN, "u1", DENY, NOW);
    verify(repository).upsert(SIGN, "u2", DENY, NOW);
    verify(repository).upsert(SIGN, "u3", DENY, NOW);
    ArgumentCaptor<SignState> updates = ArgumentCaptor.forClass(SignState.class);
    verify(repository, org.mockito.Mockito.times(3)).updateSign(updates.capture(), eq(NOW));
    assertThat(updates.getAllValues())
        .extracting(SignState::status)
        .containsExactly(SignStatus.APPROVED, SignStatus.APPROVED, SignStatus.UNDER_REVIEW);
    SignState last = updates.getValue();
    assertThat(last.confidence()).isGreaterThan(new BigDecimal("0.5"));
    assertThat(last.denySinceReview()).isEqualTo(3);
    assertThat(last.version()).isEqualTo(8);

    ArgumentCaptor<AuditEntry> event = ArgumentCaptor.forClass(AuditEntry.class);
    verify(audit).record(event.capture());
    assertThat(event.getValue().entityType()).isEqualTo(AuditEntityType.SIGN);
    assertThat(event.getValue().entityId()).isEqualTo(SIGN);
    assertThat(event.getValue().actorType()).isEqualTo(ActorType.SYSTEM);
    assertThat(event.getValue().action()).isEqualTo("MARKED_UNDER_REVIEW");
  }

  @Test
  void changingConfirmToDenyAdjustsBothCountersAndUsesTheTableCount() {
    when(repository.lockPublished(SIGN))
        .thenReturn(
            Optional.of(sign.toBuilder().confirmCount(1).denyCount(2).denySinceReview(99).build()));
    when(repository.previous(SIGN, "u1"))
        .thenReturn(Optional.of(new PreviousRating(CONFIRM, NOW.minusSeconds(1))));
    when(repository.countDeniesSinceReview(SIGN, REVIEWED)).thenReturn(1);

    service.rate(SIGN, "u1", DENY);

    verify(repository).upsert(SIGN, "u1", DENY, NOW);
    ArgumentCaptor<SignState> updates = ArgumentCaptor.forClass(SignState.class);
    verify(repository).updateSign(updates.capture(), eq(NOW));
    assertThat(updates.getValue().confirmCount()).isZero();
    assertThat(updates.getValue().denyCount()).isEqualTo(3);
    assertThat(updates.getValue().denySinceReview()).isEqualTo(2);
    assertThat(updates.getValue().status()).isEqualTo(SignStatus.APPROVED);
    verifyNoInteractions(audit);
  }

  @Test
  void rejectsTheSubmitter() {
    when(repository.lockPublished(SIGN)).thenReturn(Optional.of(sign));
    when(repository.hasSubmission(SIGN, "u1")).thenReturn(true);

    assertThatThrownBy(() -> service.rate(SIGN, "u1", CONFIRM))
        .isInstanceOfSatisfying(
            ApiException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.BUSINESS_RULE));
    verifyNoInteractions(audit);
  }
}
