package vn.signa.sign.internal.feedback;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import vn.signa.shared.error.ApiException;
import vn.signa.shared.error.ErrorCode;
import vn.signa.shared.storage.Bucket;
import vn.signa.shared.storage.ObjectStorage;
import vn.signa.sign.internal.feedback.ReportRepository.ReportRow;

@Service
public class ReportService {

  public static final int MAX_PHOTO_BYTES = ReportPhoto.MAX_BYTES;

  private final ReportRepository reports;
  private final ObjectStorage storage;
  private final Clock clock;

  ReportService(ReportRepository reports, ObjectStorage storage, Clock clock) {
    this.reports = reports;
    this.storage = storage;
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
}
