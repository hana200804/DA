package vn.signa.catalog.internal;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import vn.signa.audit.api.AuditEntityType;
import vn.signa.audit.api.AuditEntry;
import vn.signa.audit.api.AuditRecorder;
import vn.signa.catalog.internal.CatalogRepository.CategoryRow;
import vn.signa.catalog.internal.CatalogRepository.TypeRow;

/**
 * Nạp danh mục nhãn vào {@code sign_categories}, {@code sign_types} (docs/06 §5). Upsert theo
 * {@code code}, trong một giao dịch, nạp lại bao nhiêu lần cũng cho cùng kết quả.
 *
 * <ul>
 *   <li>Nhóm chưa có thì thêm; nhóm đã có giữ nguyên vì mọi trường của nhóm do quản trị viên sửa.
 *   <li>Loại chưa có thì thêm. Loại đã có thì cập nhật {@code category_code}, {@code speed_kmh},
 *       {@code label_id}, {@code label_map_version}, {@code label_name}, và {@code name} chỉ khi
 *       {@code name} còn bằng tên lấy từ file lần trước. Không đụng {@code description}, {@code
 *       icon_key}, {@code active}.
 *   <li>Loại có trong CSDL nhưng không có trong file giữ nguyên; nó không còn khớp phiên bản mới.
 * </ul>
 */
@Service
public class LabelImporter {

  /** Kết quả một lần nạp. */
  public record Result(
      int version,
      int categoriesCreated,
      int typesCreated,
      int typesUpdated,
      int typesUnchanged,
      List<String> notInFile) {}

  private static final Logger log = LoggerFactory.getLogger(LabelImporter.class);

  private final CatalogRepository repository;
  private final AuditRecorder audit;
  private final SignTypesCache cache;
  private final TransactionTemplate tx;
  private final Clock clock;

  LabelImporter(
      CatalogRepository repository,
      AuditRecorder audit,
      SignTypesCache cache,
      TransactionTemplate tx,
      Clock clock) {
    this.repository = repository;
    this.audit = audit;
    this.cache = cache;
    this.tx = tx;
    this.clock = clock;
  }

  /** Đọc, kiểm tra rồi nạp file; file sai thì ném {@link LabelsFileException}, không ghi gì. */
  public Result importFrom(Path path) {
    LabelMap map = LabelsYamlParser.load(path);
    Result result = tx.execute(status -> apply(map));
    log.info(
        "Nạp danh mục nhãn {} v{}: thêm {} nhóm, thêm {} loại, cập nhật {} loại, giữ nguyên {}",
        path,
        result.version(),
        result.categoriesCreated(),
        result.typesCreated(),
        result.typesUpdated(),
        result.typesUnchanged());
    if (!result.notInFile().isEmpty()) {
      log.warn("Loại nhận diện được nhưng không có trong file: {}", result.notInFile());
    }
    return result;
  }

  private Result apply(LabelMap map) {
    Instant now = clock.instant().truncatedTo(ChronoUnit.MILLIS);
    int version = map.version();

    Set<String> categoryCodes =
        repository.categories().stream().map(CategoryRow::code).collect(Collectors.toSet());
    Map<String, String> codeByKey = new LinkedHashMap<>();
    int categoriesCreated = 0;
    for (LabelMap.Category c : map.categories()) {
      codeByKey.put(c.key(), c.code());
      if (categoryCodes.contains(c.code())) continue;
      CategoryRow row = new CategoryRow(c.code(), c.name(), null, c.sortOrder());
      repository.insertCategory(row);
      audit.record(
          AuditEntry.bySystem(
              AuditEntityType.SIGN_CATEGORY, c.code(), "CREATED", null, fields(row), null));
      categoriesCreated++;
    }

    Map<String, TypeRow> existing =
        repository.allTypes().stream()
            .collect(Collectors.toMap(TypeRow::code, Function.identity()));
    int created = 0;
    int updated = 0;
    int unchanged = 0;
    for (LabelMap.LabelClass c : map.classes()) {
      String categoryCode = codeByKey.get(c.categoryKey());
      TypeRow old = existing.remove(c.code());
      if (old == null) {
        TypeRow row =
            new TypeRow(
                c.code(),
                categoryCode,
                c.name(),
                null,
                c.speedKmh(),
                null,
                c.id(),
                version,
                c.name(),
                true);
        repository.insertType(row, now);
        audit.record(
            AuditEntry.bySystem(
                AuditEntityType.SIGN_TYPE, c.code(), "CREATED", null, fields(row), null));
        created++;
        continue;
      }
      boolean nameUntouched = Objects.equals(old.name(), old.labelName());
      TypeRow row =
          new TypeRow(
              old.code(),
              categoryCode,
              nameUntouched ? c.name() : old.name(),
              old.description(),
              c.speedKmh(),
              old.iconKey(),
              c.id(),
              version,
              c.name(),
              old.active());
      Map<String, Object> before = fields(old);
      Map<String, Object> after = fields(row);
      Set<String> same =
          before.keySet().stream()
              .filter(k -> Objects.equals(before.get(k), after.get(k)))
              .collect(Collectors.toSet());
      if (same.size() == before.size()) {
        unchanged++;
        continue;
      }
      before.keySet().removeAll(same);
      after.keySet().removeAll(same);
      repository.updateLabelFields(row, now);
      audit.record(
          AuditEntry.bySystem(
              AuditEntityType.SIGN_TYPE, row.code(), "UPDATED", before, after, null));
      updated++;
    }

    List<String> notInFile = new ArrayList<>();
    existing.values().stream()
        .filter(t -> t.labelId() != null && t.active())
        .forEach(t -> notInFile.add(t.code()));

    cache.evictAfterCommit();
    return new Result(version, categoriesCreated, created, updated, unchanged, notInFile);
  }

  private static Map<String, Object> fields(CategoryRow row) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("code", row.code());
    m.put("name", row.name());
    m.put("sortOrder", row.sortOrder());
    return m;
  }

  /** Các trường lệnh nạp nhãn có thể ghi; dùng cho lịch sử và để so thay đổi. */
  private static Map<String, Object> fields(TypeRow row) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("categoryCode", row.categoryCode());
    m.put("name", row.name());
    m.put("speedKmh", row.speedKmh());
    m.put("labelId", row.labelId());
    m.put("labelMapVersion", row.labelMapVersion());
    m.put("labelName", row.labelName());
    return m;
  }
}
