package vn.signa.sign.internal.feedback;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import vn.signa.audit.api.AuditEntityType;
import vn.signa.audit.api.AuditEntry;
import vn.signa.audit.api.AuditRecorder;
import vn.signa.catalog.api.SignCatalog;
import vn.signa.catalog.api.SignTypeView;
import vn.signa.identity.api.UserDirectory;
import vn.signa.shared.error.ApiException;
import vn.signa.shared.error.ErrorCode;
import vn.signa.shared.storage.Bucket;
import vn.signa.shared.storage.ObjectStorage;
import vn.signa.sign.clustering.SignStatus;
import vn.signa.sign.internal.feedback.ReportRepository.ReportRow;
import vn.signa.sign.internal.feedback.ReportRepository.SignRow;
import vn.signa.sign.internal.map.MapResult.LatLng;
import vn.signa.sign.internal.review.ReviewRequests.Edit;
import vn.signa.sign.internal.review.ReviewRequests.Reasoned;
import vn.signa.sign.internal.review.ReviewService;

@Service
public class ReportService {

  public static final int MAX_PHOTO_BYTES = ReportPhoto.MAX_BYTES;
  static final Duration PHOTO_RETENTION = Duration.ofDays(90);
  private static final Duration PHOTO_URL_TTL = Duration.ofMinutes(10);

  private final ReportRepository reports;
  private final ObjectStorage storage;
  private final UserDirectory users;
  private final SignCatalog catalog;
  private final ReviewService reviews;
  private final AuditRecorder audit;
  private final Clock clock;

  ReportService(
      ReportRepository reports,
      ObjectStorage storage,
      UserDirectory users,
      SignCatalog catalog,
      ReviewService reviews,
      AuditRecorder audit,
      Clock clock) {
    this.reports = reports;
    this.storage = storage;
    this.users = users;
    this.catalog = catalog;
    this.reviews = reviews;
    this.audit = audit;
    this.clock = clock;
  }

  @Transactional(isolation = Isolation.READ_COMMITTED)
  public Receipt create(String signId, String userId, ReportMetadata metadata, byte[] photo) {
    ReportPhoto format = photo == null ? null : ReportPhoto.detect(photo);
    if (!reports.lockPublishedSign(signId)) throw ApiException.notFound();

    String id = UUID.randomUUID().toString();
    String photoKey = format == null ? null : id + "." + format.extension;
    if (format != null) storePhoto(photoKey, photo, format.contentType);
    try {
      reports.insert(id, signId, userId, metadata, photoKey, now());
    } catch (DuplicateKeyException e) {
      throw new ApiException(ErrorCode.CONFLICT, "Bạn đã có báo cáo đang chờ cho biển này");
    }
    return new Receipt(id, Status.PENDING);
  }

  private void storePhoto(String key, byte[] photo, String contentType) {
    TransactionSynchronizationManager.registerSynchronization(
        new TransactionSynchronization() {
          @Override
          public void afterCompletion(int status) {
            if (status == STATUS_ROLLED_BACK) storage.delete(Bucket.REPORT_PHOTOS, key);
          }
        });
    storage.put(Bucket.REPORT_PHOTOS, key, photo, contentType);
  }

  @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
  public Page<Mine> mine(String userId, int page, int size) {
    List<Mine> items = reports.findMine(userId, page, size).stream().map(Mine::from).toList();
    return new Page<>(items, page, size, reports.countMine(userId));
  }

  @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
  public Page<Group> queue(Status status, Reason reason, int page, int size) {
    List<SignRow> signs = reports.findSigns(status, reason, page, size);
    long total = reports.countSigns(status, reason);
    if (signs.isEmpty()) return new Page<>(List.of(), page, size, total);

    var rows = reports.findForSigns(signs.stream().map(SignRow::id).toList(), status, reason);
    var names =
        users.displayNames(rows.stream().map(ReportRow::userId).collect(Collectors.toSet()));
    Instant photoCutoff = now().minus(PHOTO_RETENTION);
    var bySign =
        rows.stream()
            .collect(
                Collectors.groupingBy(
                    ReportRow::signId,
                    Collectors.mapping(
                        row -> toReport(row, names.get(row.userId()), photoCutoff),
                        Collectors.toList())));
    Map<String, String> signNames = new HashMap<>();
    List<Group> groups =
        signs.stream()
            .map(row -> {
              String name =
                  signNames.computeIfAbsent(
                      row.code(),
                      code -> catalog.findType(code).map(SignTypeView::name).orElse(code));
              var sign =
                  new Sign(row.id(), row.code(), name, row.location(), row.status(), row.version());
              return new Group(sign, bySign.getOrDefault(row.id(), List.of()));
            })
            .toList();
    return new Page<>(groups, page, size, total);
  }

  @Transactional(isolation = Isolation.READ_COMMITTED)
  public void accept(String id, String actorId, ReportAcceptance request) {
    var report = reports.lockPending(id).orElseThrow(ReportService::notPending);
    Resolution resolution =
        switch (request.action()) {
          case EDIT -> {
            var changes = request.changes();
            reviews.edit(
                actorId,
                report.signId(),
                new Edit(request.version(), changes.signTypeCode(), changes.location(), null));
            yield Resolution.EDITED;
          }
          case REMOVE -> {
            reviews.remove(
                actorId,
                report.signId(),
                new Reasoned(request.version(), "Chấp nhận báo cáo " + report.id()));
            yield Resolution.REMOVED;
          }
        };

    Instant now = now();
    if (!reports.accept(report.id(), actorId, resolution, now)) throw notPending();
    audit.record(
        AuditEntry.byUser(
            actorId,
            AuditEntityType.REPORT,
            report.id(),
            Status.ACCEPTED.name(),
            Map.of("status", Status.PENDING.name()),
            Map.of(
                "status",
                Status.ACCEPTED.name(),
                "resolution",
                resolution.name(),
                "resolvedBy",
                actorId,
                "resolvedAt",
                now),
            null));
  }

  @Transactional
  public void dismiss(String id, String actorId, String note) {
    Instant now = now();
    if (!reports.dismiss(id, actorId, note, now)) {
      throw notPending();
    }
    audit.record(
        AuditEntry.byUser(
            actorId,
            AuditEntityType.REPORT,
            id,
            Status.DISMISSED.name(),
            Map.of("status", Status.PENDING.name()),
            Map.of(
                "status",
                Status.DISMISSED.name(),
                "resolutionNote",
                note,
                "resolvedBy",
                actorId,
                "resolvedAt",
                now),
            note));
  }

  private static ApiException notPending() {
    return new ApiException(ErrorCode.CONFLICT, "Báo cáo không còn ở trạng thái đang chờ");
  }

  private Report toReport(ReportRow row, String name, Instant photoCutoff) {
    String photoUrl = null;
    if (row.photoKey() != null
        && (row.resolvedAt() == null || row.resolvedAt().isAfter(photoCutoff))) {
      photoUrl = storage.presignedGet(Bucket.REPORT_PHOTOS, row.photoKey(), PHOTO_URL_TTL);
    }
    return new Report(
        row.id(),
        row.reason(),
        row.description(),
        photoUrl,
        name,
        row.createdAt(),
        row.status(),
        row.resolution(),
        row.resolutionNote());
  }

  private Instant now() {
    return clock.instant().truncatedTo(ChronoUnit.MILLIS);
  }

  public enum Reason {
    NOT_EXIST,
    WRONG_TYPE,
    WRONG_LOCATION,
    OTHER
  }

  public enum Status {
    PENDING,
    ACCEPTED,
    DISMISSED
  }

  public enum Resolution {
    EDITED,
    REMOVED
  }

  public record Page<T>(List<T> items, int page, int size, long total) {}

  public record Receipt(String id, Status status) {}

  public record Mine(
      String id,
      String signId,
      Reason reason,
      String description,
      Status status,
      Resolution resolution,
      String resolutionNote,
      Instant createdAt,
      Instant resolvedAt) {
    private static Mine from(ReportRow row) {
      return new Mine(
          row.id(),
          row.signId(),
          row.reason(),
          row.description(),
          row.status(),
          row.resolution(),
          row.resolutionNote(),
          row.createdAt(),
          row.resolvedAt());
    }
  }

  public record Sign(
      String id,
      String signTypeCode,
      String signName,
      LatLng location,
      SignStatus status,
      int version) {}

  public record Report(
      String id,
      Reason reason,
      String description,
      String photoUrl,
      String reporterName,
      Instant createdAt,
      Status status,
      Resolution resolution,
      String resolutionNote) {}

  public record Group(Sign sign, List<Report> reports) {}
}
