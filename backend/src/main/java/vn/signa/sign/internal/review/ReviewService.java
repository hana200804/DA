package vn.signa.sign.internal.review;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import vn.signa.audit.api.AuditEntityType;
import vn.signa.audit.api.AuditEntry;
import vn.signa.audit.api.AuditRecorder;
import vn.signa.catalog.api.SignCatalog;
import vn.signa.catalog.api.SignTypeView;
import vn.signa.shared.error.ApiException;
import vn.signa.shared.error.ErrorCode;
import vn.signa.sign.clustering.SignState;
import vn.signa.sign.internal.review.ReviewRequests.Approve;
import vn.signa.sign.internal.review.ReviewRequests.Edit;
import vn.signa.sign.internal.review.ReviewRequests.Keep;
import vn.signa.sign.internal.review.ReviewRequests.NewSign;
import vn.signa.sign.internal.review.ReviewRequests.Reasoned;
import vn.signa.sign.internal.review.ReviewRules.Outcome;
import vn.signa.sign.internal.review.ReviewRules.Target;
import vn.signa.sign.internal.review.ReviewViews.Created;

/**
 * Thao tác ghi của kiểm duyệt viên (FR-31, FR-33). Mỗi thao tác là một giao dịch: khoá biển, kiểm
 * {@code version} và trạng thái, ghi biển bằng {@code UPDATE … WHERE id = ? AND version = ?}, cập
 * nhật đề xuất thành viên và lịch sử (NFR-11). Không đổi {@code sign_code_weights}.
 */
@Service
public class ReviewService {

  private final ReviewSignRepository signs;
  private final SignCatalog catalog;
  private final AuditRecorder audit;
  private final Clock clock;

  ReviewService(ReviewSignRepository signs, SignCatalog catalog, AuditRecorder audit, Clock clock) {
    this.signs = signs;
    this.catalog = catalog;
    this.audit = audit;
    this.clock = clock;
  }

  @Transactional(isolation = Isolation.READ_COMMITTED)
  public void approve(String actor, String id, Approve request) {
    Target target = target(request.signTypeCode());
    Instant now = now();
    write(
        actor,
        id,
        sign ->
            ReviewRules.approve(sign, request.version(), target, request.change().location(), now),
        now);
  }

  @Transactional(isolation = Isolation.READ_COMMITTED)
  public void reject(String actor, String id, Reasoned request) {
    Instant now = now();
    write(
        actor, id, sign -> ReviewRules.reject(sign, request.version(), request.reason(), now), now);
  }

  @Transactional(isolation = Isolation.READ_COMMITTED)
  public void keep(String actor, String id, Keep request) {
    Target target = target(request.signTypeCode());
    Instant now = now();
    write(
        actor,
        id,
        sign -> ReviewRules.keep(sign, request.version(), request.change(), target, now),
        now);
  }

  @Transactional(isolation = Isolation.READ_COMMITTED)
  public void remove(String actor, String id, Reasoned request) {
    Instant now = now();
    write(
        actor, id, sign -> ReviewRules.remove(sign, request.version(), request.reason(), now), now);
  }

  @Transactional(isolation = Isolation.READ_COMMITTED)
  public void edit(String actor, String id, Edit request) {
    SignChange change = request.change();
    Target target = target(change.signTypeCode());
    write(actor, id, sign -> ReviewRules.edit(sign, request.version(), change, target), now());
  }

  @Transactional(isolation = Isolation.READ_COMMITTED)
  public Created create(String actor, NewSign request) {
    Target target = target(request.signTypeCode());
    Instant now = now();
    Outcome outcome =
        ReviewRules.create(
            UUID.randomUUID().toString(),
            target,
            request.location().toPoint(),
            request.headingDeg(),
            now);
    signs.insert(outcome.sign(), actor, now);
    record(actor, outcome);
    return new Created(outcome.sign().id(), outcome.sign().version());
  }

  private void write(String actor, String id, Function<SignState, Outcome> operation, Instant now) {
    SignState sign = signs.lock(id).orElseThrow(ApiException::notFound);
    Outcome outcome = operation.apply(sign);
    boolean codeChanged = !outcome.sign().signTypeCode().equals(sign.signTypeCode());
    if (!signs.update(
        outcome.sign(), sign.version(), outcome.reviewed() ? actor : null, now, codeChanged)) {
      throw new ApiException(ErrorCode.SIGN_ALREADY_PROCESSED);
    }
    signs.updateMembers(id, outcome.members());
    record(actor, outcome);
  }

  private void record(String actor, Outcome outcome) {
    for (ReviewRules.Event event : outcome.events()) {
      audit.record(
          AuditEntry.byUser(
              actor,
              AuditEntityType.SIGN,
              outcome.sign().id(),
              event.action(),
              event.before(),
              event.after(),
              event.reason()));
    }
  }

  /** Mã phải là loại đang dùng; {@code null} khi request không đổi mã. */
  private Target target(String code) {
    if (code == null) return null;
    SignTypeView type =
        catalog
            .findTypeForSignWrite(code)
            .filter(SignTypeView::active)
            .orElseThrow(() ->
                new ApiException(
                    ErrorCode.BUSINESS_RULE,
                    "Loại biển " + code + " không tồn tại hoặc đã ngừng dùng"));
    return new Target(type.code(), type.categoryCode());
  }

  private Instant now() {
    return clock.instant().truncatedTo(ChronoUnit.MILLIS);
  }
}
