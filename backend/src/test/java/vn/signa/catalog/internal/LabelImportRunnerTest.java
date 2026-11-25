package vn.signa.catalog.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.transaction.support.TransactionTemplate;
import vn.signa.catalog.internal.CatalogRepository.CategoryRow;
import vn.signa.catalog.internal.CatalogRepository.TypeRow;
import vn.signa.catalog.internal.LabelImporterTest.FakeRepository;
import vn.signa.catalog.internal.LabelImporterTest.NoOpTransactionManager;
import vn.signa.shared.config.SignaProperties;

class LabelImportRunnerTest {

  @TempDir Path directory;
  private final FakeRepository repository = new FakeRepository();
  private final LabelImporter importer =
      new LabelImporter(
          repository,
          entry -> {},
          event -> {},
          new TransactionTemplate(new NoOpTransactionManager()),
          Clock.systemUTC());

  @Test
  void startupImportsWhenNoLabelVersionHasBeenStored() throws IOException {
    runner(true, labels(1)).run(new DefaultApplicationArguments());

    assertThat(repository.types.keySet()).containsExactly("A.1", "A.2");
    assertThat(repository.types.get("A.1").labelMapVersion()).isEqualTo(1);
  }

  @Test
  void startupInsertsMissingRowsWithoutEditingExistingRows() throws IOException {
    TypeRow edited = seedEditedType();

    runner(true, labels(1)).run(new DefaultApplicationArguments());

    assertThat(repository.types.get("A.1")).isEqualTo(edited);
    assertThat(repository.types.get("A.2").labelMapVersion()).isEqualTo(1);
    assertThat(repository.categories).containsKey("MANDATORY");
    assertThat(repository.generation()).isEqualTo(2);
    assertThat(repository.updates).isZero();
  }

  @Test
  void startupSynchronizesWhenFileVersionIsNewer() throws IOException {
    seedEditedType();

    runner(true, labels(2)).run(new DefaultApplicationArguments());

    assertThat(repository.types.get("A.1").labelMapVersion()).isEqualTo(2);
    assertThat(repository.types.get("A.1").speedKmh()).isEqualTo(50);
    assertThat(repository.types.get("A.2").labelMapVersion()).isEqualTo(2);
  }

  @Test
  void startupSkipsWhenFileVersionIsOlder() throws IOException {
    Path old = labels(1);
    importer.importFrom(labels(2));
    long generation = repository.generation();

    runner(true, old).run(new DefaultApplicationArguments());

    assertThat(repository.types.get("A.1").labelMapVersion()).isEqualTo(2);
    assertThat(repository.generation()).isEqualTo(generation);
  }

  @Test
  void explicitOptionSynchronizesEvenAtSameVersionAndWithStartupDisabled() throws IOException {
    seedEditedType();

    runner(false, labels(1)).run(new DefaultApplicationArguments("--import-labels"));

    assertThat(repository.types.get("A.1").speedKmh()).isEqualTo(50);
    assertThat(repository.updates).isEqualTo(1);
  }

  private TypeRow seedEditedType() {
    repository.insertCategory(new CategoryRow("OTHER", "Nhóm đã sửa", null, 1));
    TypeRow edited = new TypeRow("A.1", "OTHER", "Tên đã sửa", null, 55, null, 0, 1, "A", false);
    repository.types.put(edited.code(), edited);
    return edited;
  }

  private Path labels(int version) throws IOException {
    return Files.writeString(
        directory.resolve("labels-v" + version + ".yaml"),
        """
        version: %d
        categories:
          other: { name: Biển khác }
          mandatory: { name: Biển hiệu lệnh }
        classes:
          - { id: 0, code: A.1, category: other, name: A, speed_kmh: 50 }
          - { id: 1, code: A.2, category: mandatory, name: B }
        """
            .formatted(version));
  }

  private LabelImportRunner runner(boolean importOnStartup, Path path) {
    return new LabelImportRunner(
        importer,
        new CatalogProperties(importOnStartup, Duration.ofHours(24)),
        new SignaProperties(null, null, path.toString()));
  }
}
