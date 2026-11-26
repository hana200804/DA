package vn.signa.catalog.internal;

import static vn.signa.audit.api.AuditEntityType.SIGN_CATEGORY;
import static vn.signa.audit.api.AuditEntityType.SIGN_TYPE;
import static vn.signa.catalog.internal.CatalogAdminRequests.categoryCode;
import static vn.signa.catalog.internal.CatalogAdminRequests.description;
import static vn.signa.catalog.internal.CatalogAdminRequests.typeCode;
import static vn.signa.shared.storage.Bucket.SIGN_ICONS;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import vn.signa.audit.api.AuditEntityType;
import vn.signa.audit.api.AuditEntry;
import vn.signa.audit.api.AuditRecorder;
import vn.signa.catalog.api.SignTypeCategoryChanged;
import vn.signa.catalog.internal.CatalogAdminRequests.EditCategory;
import vn.signa.catalog.internal.CatalogAdminRequests.EditType;
import vn.signa.catalog.internal.CatalogAdminRequests.NewCategory;
import vn.signa.catalog.internal.CatalogAdminRequests.NewType;
import vn.signa.catalog.internal.CatalogRepository.AdminTypeRow;
import vn.signa.catalog.internal.CatalogRepository.CategoryRow;
import vn.signa.catalog.internal.CatalogRepository.TypeRow;
import vn.signa.catalog.internal.CatalogResponses.AdminSignTypeItem;
import vn.signa.catalog.internal.CatalogResponses.AdminSignTypeList;
import vn.signa.catalog.internal.CatalogResponses.SignCategoryItem;
import vn.signa.shared.error.ApiException;
import vn.signa.shared.error.ErrorCode;
import vn.signa.shared.image.ImageFormat;
import vn.signa.shared.image.ImageValidation;
import vn.signa.shared.storage.ObjectStorage;
import vn.signa.shared.storage.ObjectStorageException;

/** Thay đổi danh mục và ghi lịch sử trong cùng giao dịch (FR-35). */
@Service
public class CatalogAdminService {

  private static final int MAX_ICON_EDGE = 4096;
  private static final long MAX_ICON_PIXELS = 4_000_000L;
  private static final Logger log = LoggerFactory.getLogger(CatalogAdminService.class);

  private final CatalogRepository repository;
  private final AuditRecorder audit;
  private final ApplicationEventPublisher events;
  private final ObjectStorage storage;
  private final TransactionTemplate cleanupTx;
  private final Clock clock;

  CatalogAdminService(
      CatalogRepository repository,
      AuditRecorder audit,
      ApplicationEventPublisher events,
      ObjectStorage storage,
      PlatformTransactionManager transactionManager,
      Clock clock) {
    this.repository = repository;
    this.audit = audit;
    this.events = events;
    this.storage = storage;
    this.cleanupTx = new TransactionTemplate(transactionManager);
    this.cleanupTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    this.clock = clock;
  }

  public AdminSignTypeList types(Boolean active) {
    List<AdminSignTypeItem> items =
        repository.adminTypes(active).stream().map(AdminSignTypeItem::of).toList();
    return new AdminSignTypeList(items);
  }

  @Transactional
  public SignCategoryItem createCategory(String actorId, NewCategory input) {
    repository.lockGeneration();
    CategoryRow row =
        new CategoryRow(
            input.code(), input.name(), description(input.description()), input.sortOrder());
    try {
      repository.insertCategory(row);
    } catch (DuplicateKeyException e) {
      throw new ApiException(ErrorCode.CONFLICT, "Mã nhóm biển đã tồn tại");
    }
    record(actorId, SIGN_CATEGORY, row.code(), "CREATED", null, fields(row));
    return SignCategoryItem.of(row);
  }

  @Transactional
  public SignCategoryItem editCategory(String actorId, String code, EditCategory input) {
    CategoryRow old =
        repository.lockCategory(categoryCode(code)).orElseThrow(ApiException::notFound);
    CategoryRow row =
        new CategoryRow(
            old.code(),
            Objects.requireNonNullElse(input.name(), old.name()),
            input.descriptionOr(old.description()),
            Objects.requireNonNullElse(input.sortOrder(), old.sortOrder()));
    if (!old.equals(row)) {
      repository.updateCategory(row);
      recordUpdate(actorId, SIGN_CATEGORY, row.code(), "UPDATED", fields(old), fields(row));
    }
    return SignCategoryItem.of(row);
  }

  @Transactional
  public AdminSignTypeItem createType(String actorId, NewType input) {
    repository.lockGeneration();
    requireCategory(input.categoryCode());
    TypeRow row =
        new TypeRow(
            input.code(),
            input.categoryCode(),
            input.name(),
            description(input.description()),
            input.speedKmh(),
            null,
            null,
            null,
            null,
            Objects.requireNonNullElse(input.active(), true));
    Instant now = now();
    try {
      repository.insertType(row, now);
    } catch (DuplicateKeyException e) {
      throw new ApiException(ErrorCode.CONFLICT, "Mã loại biển đã tồn tại");
    }
    record(actorId, SIGN_TYPE, row.code(), "CREATED", null, fields(row));
    return AdminSignTypeItem.of(new AdminTypeRow(row, now));
  }

  @Transactional
  public AdminSignTypeItem editType(String actorId, String code, EditType input) {
    repository.lockGeneration();
    AdminTypeRow current = lockType(code);
    TypeRow old = current.type();
    String category = Objects.requireNonNullElse(input.categoryCode(), old.categoryCode());
    if (!category.equals(old.categoryCode())) requireCategory(category);
    TypeRow row =
        new TypeRow(
            old.code(),
            category,
            Objects.requireNonNullElse(input.name(), old.name()),
            input.descriptionOr(old.description()),
            input.speedOr(old.speedKmh()),
            old.iconKey(),
            old.labelId(),
            old.labelMapVersion(),
            old.labelName(),
            Objects.requireNonNullElse(input.active(), old.active()));
    if (old.equals(row)) return AdminSignTypeItem.of(current);

    Instant now = now();
    repository.updateAdminFields(row, now);
    if (!category.equals(old.categoryCode())) {
      events.publishEvent(new SignTypeCategoryChanged(row.code(), category, now));
    }
    String action = old.active() && !row.active() ? "DEACTIVATED" : "UPDATED";
    recordUpdate(actorId, SIGN_TYPE, row.code(), action, fields(old), fields(row));
    return AdminSignTypeItem.of(new AdminTypeRow(row, now));
  }

  @Transactional
  public void uploadIcon(String actorId, String code, byte[] bytes) {
    ImageFormat format = ImageValidation.validate(bytes, MAX_ICON_EDGE, MAX_ICON_PIXELS);
    repository.lockGeneration();
    TypeRow row = lockType(code).type();
    String key = row.code() + "-" + UUID.randomUUID() + "." + format.extension();
    scheduleIconCleanup(row.code(), row.iconKey(), key);
    storage.put(SIGN_ICONS, key, bytes, format.mediaType().toString());
    repository.updateIcon(row.code(), key, now());
    Map<String, Object> before = new LinkedHashMap<>();
    before.put("iconKey", row.iconKey());
    record(actorId, SIGN_TYPE, row.code(), "UPDATED", before, Map.of("iconKey", key));
  }

  private AdminTypeRow lockType(String code) {
    return repository.lockType(typeCode(code)).orElseThrow(ApiException::notFound);
  }

  private void requireCategory(String categoryCode) {
    if (!repository.categoryExists(categoryCode)) {
      throw CatalogAdminRequests.invalid("categoryCode", "Không tìm thấy nhóm biển");
    }
  }

  private Instant now() {
    return clock.instant().truncatedTo(ChronoUnit.MILLIS);
  }

  private void record(
      String actorId,
      AuditEntityType entity,
      String code,
      String action,
      Map<String, ?> before,
      Map<String, ?> after) {
    audit.record(AuditEntry.byUser(actorId, entity, code, action, before, after, null));
    if (entity == SIGN_TYPE) repository.advanceGeneration();
  }

  private void recordUpdate(
      String actorId,
      AuditEntityType entity,
      String code,
      String action,
      Map<String, Object> before,
      Map<String, Object> after) {
    before
        .entrySet()
        .removeIf(entry -> Objects.equals(entry.getValue(), after.get(entry.getKey())));
    after.keySet().retainAll(before.keySet());
    record(actorId, entity, code, action, before, after);
  }

  private void scheduleIconCleanup(String code, String oldKey, String newKey) {
    TransactionSynchronizationManager.registerSynchronization(
        new TransactionSynchronization() {
          @Override
          public void afterCompletion(int status) {
            if (status == STATUS_COMMITTED && oldKey != null) {
              deleteUnusedIcon(code, oldKey);
            } else if (status == STATUS_ROLLED_BACK) {
              deleteUnusedIcon(code, newKey);
            }
          }
        });
  }

  private void deleteUnusedIcon(String code, String key) {
    // Kiểm lại với khoá hàng để tránh xoá ảnh mà một request khác vừa chọn lại.
    try {
      cleanupTx.executeWithoutResult(status -> {
        String currentKey = repository.lockType(code).map(row -> row.type().iconKey()).orElse(null);
        if (!key.equals(currentKey)) storage.delete(SIGN_ICONS, key);
      });
    } catch (DataAccessException | TransactionException | ObjectStorageException e) {
      log.warn("Không xoá được ảnh minh hoạ cũ {}", key, e);
    }
  }

  private static Map<String, Object> fields(CategoryRow row) {
    Map<String, Object> fields = new LinkedHashMap<>();
    fields.put("name", row.name());
    fields.put("description", row.description());
    fields.put("sortOrder", row.sortOrder());
    return fields;
  }

  private static Map<String, Object> fields(TypeRow row) {
    Map<String, Object> fields = new LinkedHashMap<>();
    fields.put("categoryCode", row.categoryCode());
    fields.put("name", row.name());
    fields.put("description", row.description());
    fields.put("speedKmh", row.speedKmh());
    fields.put("active", row.active());
    return fields;
  }
}
