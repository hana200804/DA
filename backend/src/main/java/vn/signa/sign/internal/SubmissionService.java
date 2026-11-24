package vn.signa.sign.internal;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;
import vn.signa.catalog.api.SignCatalog;
import vn.signa.catalog.api.SignTypeView;
import vn.signa.shared.error.ApiException;
import vn.signa.shared.error.ErrorCode;
import vn.signa.shared.storage.Bucket;
import vn.signa.shared.storage.ObjectStorage;
import vn.signa.sign.clustering.GeoPoint;
import vn.signa.sign.clustering.SubmissionStatus;

@Service
public class SubmissionService {

  public static final int MAX_CROP_BYTES = 200 * 1024;
  private static final Logger log = LoggerFactory.getLogger(SubmissionService.class);

  private final SubmissionRepository submissions;
  private final SignCatalog catalog;
  private final ObjectStorage storage;
  private final SubmissionStream stream;
  private final JsonMapper json;
  private final Clock clock;
  private final TransactionTemplate insert;

  SubmissionService(
      SubmissionRepository submissions,
      SignCatalog catalog,
      ObjectStorage storage,
      SubmissionStream stream,
      JsonMapper json,
      Clock clock,
      PlatformTransactionManager transactions) {
    this.submissions = submissions;
    this.catalog = catalog;
    this.storage = storage;
    this.stream = stream;
    this.json = json;
    this.clock = clock;
    this.insert = new TransactionTemplate(transactions);
    this.insert.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
  }

  public Result submit(String user, byte[] metadataJson, byte[] crop) {
    Instant receivedAt = clock.instant().truncatedTo(ChronoUnit.MILLIS);
    SubmissionMetadata metadata = SubmissionMetadata.read(json, metadataJson);
    validateCrop(crop);
    String clientId = metadata.clientId().toString();
    Optional<Receipt> previous = submissions.find(user, clientId);
    if (previous.isPresent()) return new Result(previous.get(), false);

    if (!catalog.supportedLabelMapVersions().contains(metadata.labelMapVersion())) {
      throw new ApiException(ErrorCode.BUSINESS_RULE, "Phiên bản danh mục nhãn không được hỗ trợ");
    }
    String code =
        catalog
            .findType(metadata.signTypeCode())
            .filter(SignTypeView::active)
            .map(SignTypeView::code)
            .orElseThrow(
                () -> SubmissionMetadata.invalid("signTypeCode", "phải là mã biển đang dùng"));

    String id = UUID.randomUUID().toString();
    String key = id + ".jpg";
    storage.put(Bucket.SIGN_CROPS, key, crop, "image/jpeg");
    try {
      insert.executeWithoutResult(
          tx -> submissions.insert(id, user, code, metadata, key, receivedAt));
    } catch (DuplicateKeyException e) {
      deleteUncommittedCrop(key);
      return new Result(submissions.find(user, clientId).orElseThrow(() -> e), false);
    }
    try {
      stream.add(id);
    } catch (RuntimeException e) {
      log.warn("Không đưa được đề xuất {} vào hàng đợi sau commit", id, e);
    }
    return new Result(new Receipt(id, SubmissionStatus.RECEIVED), true);
  }

  @Transactional(readOnly = true)
  public Page list(String user, int page, int size) {
    return new Page(submissions.items(user, page, size), page, size, submissions.count(user));
  }

  private void deleteUncommittedCrop(String key) {
    try {
      storage.delete(Bucket.SIGN_CROPS, key);
    } catch (RuntimeException e) {
      log.warn("Không xoá được ảnh gửi trùng {}", key, e);
    }
  }

  private static void validateCrop(byte[] crop) {
    if (crop == null) throw SubmissionMetadata.invalid("crop", "phải có ảnh cắt");
    if (crop.length > MAX_CROP_BYTES) throw new ApiException(ErrorCode.PAYLOAD_TOO_LARGE);
    if (crop.length < 3
        || crop[0] != (byte) 0xff
        || crop[1] != (byte) 0xd8
        || crop[2] != (byte) 0xff) {
      throw new ApiException(ErrorCode.UNSUPPORTED_MEDIA_TYPE, "Ảnh cắt phải là JPEG");
    }
  }

  public record Receipt(String id, SubmissionStatus status) {}

  public record Result(Receipt receipt, boolean created) {}

  public record Page(List<Item> items, int page, int size, long total) {}

  public record Item(
      String id,
      String signTypeCode,
      SubmissionStatus status,
      String rejectReason,
      String signId,
      GeoPoint location,
      Instant receivedAt,
      Instant capturedAt) {}
}
