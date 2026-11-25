package vn.signa.catalog.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;
import vn.signa.audit.api.ActorType;
import vn.signa.audit.api.AuditEntityType;
import vn.signa.audit.api.AuditEntry;
import vn.signa.catalog.internal.CatalogRepository.CategoryRow;
import vn.signa.catalog.internal.CatalogRepository.TypeRow;

/**
 * Logic upsert của lệnh nạp nhãn trên kho trong bộ nhớ; bản chạy MySQL là {@code LabelImportIT}.
 */
class LabelImporterTest {

  private final FakeRepository repository = new FakeRepository();
  private final List<AuditEntry> audit = new ArrayList<>();
  private LabelImporter importer;

  @BeforeEach
  void setUp() {
    importer =
        new LabelImporter(
            repository,
            audit::add,
            event -> {},
            new TransactionTemplate(new NoOpTransactionManager()),
            Clock.fixed(Instant.parse("2026-11-23T00:00:00Z"), ZoneOffset.UTC));
  }

  @Test
  void importsRealFileOnceThenNothingChanges() {
    LabelImporter.Result first = importer.importFrom(LabelsYamlParserTest.REAL_FILE);

    assertThat(first.categoriesCreated()).isEqualTo(5);
    assertThat(first.typesCreated()).isEqualTo(46);
    assertThat(repository.types).hasSize(46);
    assertThat(repository.types.get("P.127_50"))
        .isEqualTo(
            new TypeRow(
                "P.127_50",
                "PROHIBITORY",
                "Tốc độ tối đa cho phép 50 km/h",
                null,
                50,
                null,
                13,
                1,
                "Tốc độ tối đa cho phép 50 km/h",
                true));
    assertThat(audit)
        .hasSize(51)
        .allSatisfy(entry -> {
          assertThat(entry.action()).isEqualTo("CREATED");
          assertThat(entry.actorType()).isEqualTo(ActorType.SYSTEM);
          assertThat(entry.actorId()).isNull();
          assertThat(entry.before()).isNull();
          assertThat(entry.after()).isNotEmpty();
        });
    Map<String, TypeRow> snapshot = new TreeMap<>(repository.types);

    audit.clear();
    LabelImporter.Result second = importer.importFrom(LabelsYamlParserTest.REAL_FILE);

    assertThat(second.categoriesCreated()).isZero();
    assertThat(second.typesCreated()).isZero();
    assertThat(second.typesUpdated()).isZero();
    assertThat(second.typesUnchanged()).isEqualTo(46);
    assertThat(repository.types).isEqualTo(snapshot);
    assertThat(repository.updates).isZero();
    assertThat(audit).isEmpty();
    assertThat(repository.generation()).isEqualTo(2);
  }

  @Test
  void keepsAdminEditsAndUpdatesUntouchedName(@TempDir Path dir) throws IOException {
    repository.categories.put("OTHER", new CategoryRow("OTHER", "Tên admin sửa", "Mô tả", 9));
    repository.types.put(
        "A.1", new TypeRow("A.1", "OTHER", "A cũ", null, null, "a.png", 0, 1, "A cũ", false));
    // Mô phỏng PATCH hoàn tất sau khi bắt đầu nạp, trước lúc khoá loại biển.
    repository.beforeLock =
        code -> {
          if (code.equals("A.1")) {
            repository.types.put(
                code,
                new TypeRow(
                    code, "OTHER", "Tên admin", "Mô tả admin", null, "a.png", 0, 1, "A cũ", false));
          }
        };
    // Tên chưa sửa: name bằng label_name.
    repository.types.put(
        "A.2", new TypeRow("A.2", "OTHER", "B cũ", "Mô tả 2", null, null, 1, 1, "B cũ", true));
    // Loại chỉ nhập tay, không có trong file.
    repository.types.put(
        "M.1", new TypeRow("M.1", "OTHER", "Nhập tay", null, null, null, null, null, null, true));

    Path file = dir.resolve("labels.yaml");
    Files.writeString(
        file,
        """
        version: 2
        categories:
          other: { name: Biển khác }
        classes:
          - { id: 5, code: A.1, category: other, name: A mới }
          - { id: 6, code: A.2, category: other, name: B mới, speed_kmh: 30 }
        """);

    LabelImporter.Result result = importer.importFrom(file);

    assertThat(result.typesUpdated()).isEqualTo(2);
    assertThat(result.notInFile()).isEmpty();
    assertThat(repository.categories.get("OTHER").name()).isEqualTo("Tên admin sửa");
    assertThat(repository.types.get("A.1"))
        .isEqualTo(
            new TypeRow(
                "A.1", "OTHER", "Tên admin", "Mô tả admin", null, "a.png", 5, 2, "A mới", false));
    assertThat(repository.types.get("A.2"))
        .isEqualTo(new TypeRow("A.2", "OTHER", "B mới", "Mô tả 2", 30, null, 6, 2, "B mới", true));
    assertThat(repository.types.get("M.1").labelId()).isNull();
    assertThat(audit)
        .filteredOn(entry -> entry.entityId().equals("A.2"))
        .singleElement()
        .satisfies(entry -> {
          assertThat(entry.entityType()).isEqualTo(AuditEntityType.SIGN_TYPE);
          assertThat(entry.action()).isEqualTo("UPDATED");
          Map<String, Object> before =
              new HashMap<>(
                  Map.of("name", "B cũ", "labelId", 1, "labelMapVersion", 1, "labelName", "B cũ"));
          before.put("speedKmh", null);
          assertThat(entry.before()).isEqualTo(before);
          assertThat(entry.after())
              .isEqualTo(
                  Map.of(
                      "name",
                      "B mới",
                      "speedKmh",
                      30,
                      "labelId",
                      6,
                      "labelMapVersion",
                      2,
                      "labelName",
                      "B mới"));
        });
  }

  @Test
  void invalidFileWritesNothing(@TempDir Path dir) throws IOException {
    Path file = dir.resolve("labels.yaml");
    Files.writeString(
        file,
        """
        version: 1
        categories:
          other: { name: Biển khác }
        classes:
          - { id: 0, code: A.1, category: nope, name: A }
        """);

    assertThatThrownBy(() -> importer.importFrom(file))
        .isInstanceOf(LabelsFileException.class)
        .hasMessageContaining("dòng 5: nhóm `nope` không có trong `categories`");
    assertThat(repository.categories).isEmpty();
    assertThat(repository.types).isEmpty();
  }

  /** Kho trong bộ nhớ thay cho MySQL. */
  static final class FakeRepository extends CatalogRepository {
    final Map<String, CategoryRow> categories = new TreeMap<>();
    final Map<String, TypeRow> types = new TreeMap<>();
    int updates;
    Consumer<String> beforeLock = code -> {};
    long generation = 1;

    @Override
    long generation() {
      return generation;
    }

    @Override
    void advanceGeneration() {
      generation++;
    }

    FakeRepository() {
      super(null);
    }

    @Override
    public List<CategoryRow> categories() {
      return List.copyOf(categories.values());
    }

    @Override
    public void insertCategory(CategoryRow row) {
      assertThat(categories.putIfAbsent(row.code(), row)).isNull();
    }

    @Override
    Optional<AdminTypeRow> lockType(String code) {
      beforeLock.accept(code);
      return type(code).map(row -> new AdminTypeRow(row, Instant.EPOCH));
    }

    @Override
    public List<TypeRow> allTypes() {
      return List.copyOf(types.values());
    }

    @Override
    public List<TypeRow> activeTypes() {
      return types.values().stream().filter(TypeRow::active).toList();
    }

    @Override
    public Optional<TypeRow> type(String code) {
      return Optional.ofNullable(types.get(code));
    }

    @Override
    public Set<Integer> recognizableVersions() {
      return Set.of();
    }

    @Override
    public void insertType(TypeRow row, Instant now) {
      assertThat(categories).containsKey(row.categoryCode());
      assertThat(types.putIfAbsent(row.code(), row)).isNull();
    }

    @Override
    public void updateLabelFields(TypeRow row, Instant now) {
      assertThat(types).containsKey(row.code());
      updates++;
      types.put(row.code(), row);
    }
  }

  /** Giao dịch rỗng nhưng vẫn chạy {@code afterCommit}. */
  static final class NoOpTransactionManager extends AbstractPlatformTransactionManager {
    @Override
    protected Object doGetTransaction() {
      return new Object();
    }

    @Override
    protected void doBegin(Object transaction, TransactionDefinition definition) {}

    @Override
    protected void doCommit(DefaultTransactionStatus status) {}

    @Override
    protected void doRollback(DefaultTransactionStatus status) {}
  }
}
