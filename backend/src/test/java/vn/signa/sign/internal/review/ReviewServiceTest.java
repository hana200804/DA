package vn.signa.sign.internal.review;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import vn.signa.audit.api.ActorType;
import vn.signa.audit.api.AuditEntityType;
import vn.signa.audit.api.AuditEntry;
import vn.signa.audit.api.AuditRecorder;
import vn.signa.catalog.api.SignCatalog;
import vn.signa.catalog.api.SignTypeView;
import vn.signa.shared.error.ApiException;
import vn.signa.shared.error.ErrorCode;
import vn.signa.sign.clustering.CodeWeight;
import vn.signa.sign.clustering.GeoPoint;
import vn.signa.sign.clustering.MemberSubmission;
import vn.signa.sign.clustering.MemberUpdate;
import vn.signa.sign.clustering.SignState;
import vn.signa.sign.clustering.SignStatus;
import vn.signa.sign.clustering.SubmissionStatus;
import vn.signa.sign.internal.review.ReviewRequests.Approve;
import vn.signa.sign.internal.review.ReviewRequests.Edit;
import vn.signa.sign.internal.review.ReviewRequests.Keep;
import vn.signa.sign.internal.review.ReviewRequests.LocationInput;
import vn.signa.sign.internal.review.ReviewRequests.NewSign;
import vn.signa.sign.internal.review.ReviewRequests.Reasoned;
import vn.signa.sign.internal.review.ReviewViews.Created;

class ReviewServiceTest {

  private static final Instant NOW = Instant.parse("2026-11-24T10:00:00.123Z");
  private static final String ACTOR = "moderator-1";
  private static final String ID = "sign-1";
  private static final GeoPoint HERE = new GeoPoint(21.0285, 105.8048);

  private final ReviewSignRepository signs = mock(ReviewSignRepository.class);
  private final SignCatalog catalog = mock(SignCatalog.class);
  private final AuditRecorder audit = mock(AuditRecorder.class);
  private final ReviewService service =
      new ReviewService(signs, catalog, audit, Clock.fixed(NOW.plusNanos(456_789), ZoneOffset.UTC));

  @BeforeEach
  void catalog() {
    when(catalog.findType("P.123b")).thenReturn(Optional.of(type("P.123b", true)));
    when(catalog.findType("P.old")).thenReturn(Optional.of(type("P.old", false)));
    when(signs.update(any(), anyInt(), any(), any())).thenReturn(true);
  }

  @Test
  void approveWritesSignWeightsMembersAndHistoryWithActor() {
    when(signs.lock(ID)).thenReturn(Optional.of(pending()));

    service.approve(ACTOR, ID, new Approve(3, "P.123b", null));

    ArgumentCaptor<SignState> written = ArgumentCaptor.forClass(SignState.class);
    verify(signs).update(written.capture(), eq(3), eq(ACTOR), eq(NOW));
    assertThat(written.getValue().status()).isEqualTo(SignStatus.APPROVED);
    assertThat(written.getValue().signTypeCode()).isEqualTo("P.123b");
    assertThat(written.getValue().reviewedAt()).isEqualTo(NOW);
    verify(signs).saveWeights(ID, Map.of("P.123a", new CodeWeight(new BigDecimal("0.0000"), 1)));
    verify(signs)
        .updateMembers(
            ID,
            List.of(
                new MemberUpdate("s1", SubmissionStatus.REJECTED, ReviewRules.REASON_OTHER_CODE),
                new MemberUpdate("s2", SubmissionStatus.ACCEPTED, null)));
    AuditEntry entry = recorded().getFirst();
    assertThat(entry.entityType()).isEqualTo(AuditEntityType.SIGN);
    assertThat(entry.entityId()).isEqualTo(ID);
    assertThat(entry.action()).isEqualTo("APPROVED");
    assertThat(entry.actorType()).isEqualTo(ActorType.USER);
    assertThat(entry.actorId()).isEqualTo(ACTOR);
  }

  @Test
  void lostVersionRaceIsAlreadyProcessedAndWritesNothingElse() {
    when(signs.lock(ID)).thenReturn(Optional.of(pending()));
    when(signs.update(any(), anyInt(), any(), any())).thenReturn(false);

    assertThatThrownBy(() -> service.approve(ACTOR, ID, new Approve(3, null, null)))
        .isInstanceOfSatisfying(
            ApiException.class,
            e -> assertThat(e.code()).isEqualTo(ErrorCode.SIGN_ALREADY_PROCESSED));
    verify(signs, never()).updateMembers(any(), any());
    verifyNoInteractions(audit);
  }

  @Test
  void staleRequestVersionIsAlreadyProcessed() {
    when(signs.lock(ID)).thenReturn(Optional.of(pending()));

    assertThatThrownBy(() -> service.reject(ACTOR, ID, new Reasoned(2, "sai")))
        .isInstanceOfSatisfying(
            ApiException.class,
            e -> assertThat(e.code()).isEqualTo(ErrorCode.SIGN_ALREADY_PROCESSED));
    verify(signs, never()).update(any(), anyInt(), any(), any());
  }

  @Test
  void missingSignIsNotFound() {
    when(signs.lock(ID)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.remove(ACTOR, ID, new Reasoned(1, "sai")))
        .isInstanceOfSatisfying(
            ApiException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.NOT_FOUND));
  }

  @Test
  void unknownOrInactiveCodeIsRejectedBeforeLocking() {
    for (String code : new String[] {"P.none", "P.old"}) {
      assertThatThrownBy(() -> service.approve(ACTOR, ID, new Approve(3, code, null)))
          .isInstanceOfSatisfying(
              ApiException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.BUSINESS_RULE));
    }
    assertThatThrownBy(
            () -> service.create(ACTOR, new NewSign("P.old", new LocationInput(21.0, 105.0), null)))
        .isInstanceOf(ApiException.class);
    verifyNoInteractions(signs, audit);
  }

  @Test
  void keepWithChangesRecordsEditedBeforeKept() {
    when(signs.lock(ID))
        .thenReturn(Optional.of(pending().toBuilder().status(SignStatus.UNDER_REVIEW).build()));

    service.keep(ACTOR, ID, new Keep(3, null, new LocationInput(21.0286, 105.8049), 10));

    assertThat(recorded()).extracting(AuditEntry::action).containsExactly("EDITED", "KEPT");
    verify(signs).update(any(), eq(3), eq(ACTOR), eq(NOW));
  }

  @Test
  void rejectAndRemoveKeepModeratorReason() {
    when(signs.lock(ID)).thenReturn(Optional.of(pending()));
    service.reject(ACTOR, ID, new Reasoned(3, "Không có biển"));
    when(signs.lock(ID))
        .thenReturn(Optional.of(pending().toBuilder().status(SignStatus.APPROVED).build()));
    service.remove(ACTOR, ID, new Reasoned(3, "Đã tháo"));

    assertThat(recorded())
        .extracting(AuditEntry::action, AuditEntry::reason)
        .containsExactly(
            org.assertj.core.groups.Tuple.tuple("REJECTED", "Không có biển"),
            org.assertj.core.groups.Tuple.tuple("REMOVED", "Đã tháo"));
  }

  @Test
  void editIsNotAReviewDecision() {
    when(signs.lock(ID))
        .thenReturn(Optional.of(pending().toBuilder().status(SignStatus.APPROVED).build()));

    service.edit(
        ACTOR, ID, new Edit(3, null, null, tools.jackson.databind.node.NullNode.getInstance()));

    verify(signs).update(any(), eq(3), isNull(), eq(NOW));
    assertThat(recorded()).extracting(AuditEntry::action).containsExactly("EDITED");
  }

  @Test
  void createInsertsApprovedSignAndHistory() {
    Created created =
        service.create(ACTOR, new NewSign("P.123b", new LocationInput(21.0, 105.0), 45));

    ArgumentCaptor<SignState> inserted = ArgumentCaptor.forClass(SignState.class);
    InOrder order = inOrder(signs, audit);
    order.verify(signs).insert(inserted.capture(), eq(ACTOR), eq(NOW));
    order.verify(audit).record(any());
    assertThat(created.version()).isZero();
    assertThat(created.id()).isEqualTo(inserted.getValue().id()).hasSize(36);
    assertThat(inserted.getValue().headingDeg()).isEqualTo(45);
    assertThat(inserted.getValue().categoryCode()).isEqualTo("PROHIBITORY");
    assertThat(recorded().getFirst().action()).isEqualTo("CREATED");
  }

  private List<AuditEntry> recorded() {
    ArgumentCaptor<AuditEntry> entries = ArgumentCaptor.forClass(AuditEntry.class);
    verify(audit, org.mockito.Mockito.atLeastOnce()).record(entries.capture());
    return entries.getAllValues();
  }

  private static SignState pending() {
    Instant received = Instant.parse("2026-11-23T10:00:00Z");
    return SignState.builder()
        .id(ID)
        .status(SignStatus.PENDING)
        .signTypeCode("P.123a")
        .categoryCode("PROHIBITORY")
        .location(HERE)
        .headingDeg(90)
        .version(3)
        .codeWeight("P.123a", new CodeWeight(new BigDecimal("0.9000"), 1))
        .codeWeight("P.123b", new CodeWeight(new BigDecimal("0.8000"), 1))
        .members(
            List.of(
                new MemberSubmission(
                    "s1",
                    "u1",
                    "P.123a",
                    new BigDecimal("0.9000"),
                    HERE,
                    received,
                    SubmissionStatus.GROUPED),
                new MemberSubmission(
                    "s2",
                    "u2",
                    "P.123b",
                    new BigDecimal("0.8000"),
                    HERE,
                    received.plusSeconds(1),
                    SubmissionStatus.GROUPED)))
        .confidence(new BigDecimal("0.5000"))
        .build();
  }

  private static SignTypeView type(String code, boolean active) {
    return new SignTypeView(code, "PROHIBITORY", "Biển " + code, null, null, null, 1, 1, active);
  }
}
