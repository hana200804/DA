package vn.signa.catalog.internal;

import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.DefaultApplicationArguments;
import vn.signa.shared.config.SignaProperties;

class LabelImportRunnerTest {

  private static final Path LABELS = LabelsYamlParserTest.REAL_FILE;
  private final LabelImporter importer = mock(LabelImporter.class);
  private final CatalogRepository repository = mock(CatalogRepository.class);

  @Test
  void startupImportsWhenNoLabelVersionHasBeenStored() {
    when(repository.latestLabelMapVersion()).thenReturn(Optional.empty());

    runner(true).run(new DefaultApplicationArguments());

    verify(importer).importParsed(eq(LABELS), argThat(map -> map.version() == 1));
  }

  @Test
  void startupSkipsWhenLabelVersionIsUnchanged() {
    when(repository.latestLabelMapVersion()).thenReturn(Optional.of(1));

    runner(true).run(new DefaultApplicationArguments());

    verifyNoInteractions(importer);
  }

  @Test
  void startupImportsWhenFileVersionIsNewer(@TempDir Path directory) throws IOException {
    Path newer = directory.resolve("labels.yaml");
    Files.writeString(
        newer, Files.readString(LABELS).replaceFirst("(?m)^version: 1$", "version: 2"));
    when(repository.latestLabelMapVersion()).thenReturn(Optional.of(1));

    runner(true, newer).run(new DefaultApplicationArguments());

    verify(importer).importParsed(eq(newer), argThat(map -> map.version() == 2));
  }

  @Test
  void startupSkipsWhenFileVersionIsOlder() {
    when(repository.latestLabelMapVersion()).thenReturn(Optional.of(2));

    runner(true).run(new DefaultApplicationArguments());

    verifyNoInteractions(importer);
  }

  @Test
  void explicitOptionBypassesStartupSettingAndVersionCheck() {
    runner(false).run(new DefaultApplicationArguments("--import-labels"));

    verify(importer).importFrom(LABELS);
    verifyNoInteractions(repository);
  }

  private LabelImportRunner runner(boolean importOnStartup) {
    return runner(importOnStartup, LABELS);
  }

  private LabelImportRunner runner(boolean importOnStartup, Path path) {
    return new LabelImportRunner(
        importer,
        repository,
        new CatalogProperties(importOnStartup, Duration.ofHours(24)),
        new SignaProperties(null, null, path.toString()));
  }
}
