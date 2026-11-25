package vn.signa.catalog.internal;

import java.nio.file.Path;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import vn.signa.shared.config.SignaProperties;

/**
 * Nạp nhãn lúc khởi động khi CSDL chưa có phiên bản nhãn hoặc khi file mới hơn phiên bản đã lưu.
 * Tham số {@code --import-labels} luôn đồng bộ bằng file. Không chạy ở profile {@code migrate}.
 */
@Component
@Profile("!migrate")
class LabelImportRunner implements ApplicationRunner {

  static final String OPTION = "import-labels";
  private static final Logger log = LoggerFactory.getLogger(LabelImportRunner.class);

  private final LabelImporter importer;
  private final CatalogRepository repository;
  private final CatalogProperties catalog;
  private final SignaProperties signa;

  LabelImportRunner(
      LabelImporter importer,
      CatalogRepository repository,
      CatalogProperties catalog,
      SignaProperties signa) {
    this.importer = importer;
    this.repository = repository;
    this.catalog = catalog;
    this.signa = signa;
  }

  @Override
  public void run(ApplicationArguments args) {
    boolean requested = args.containsOption(OPTION);
    if (!requested && !catalog.importOnStartup()) return;
    if (signa.labelsPath() == null || signa.labelsPath().isBlank()) {
      throw new IllegalStateException("Cần SIGNA_LABELS_PATH để nạp danh mục nhãn");
    }
    Path path = Path.of(signa.labelsPath());
    if (requested) {
      importer.importFrom(path);
      return;
    }

    LabelMap map = LabelsYamlParser.load(path);
    int storedVersion = repository.latestLabelMapVersion().orElse(0);
    if (map.version() <= storedVersion) {
      log.info(
          "Bỏ qua nạp danh mục nhãn lúc khởi động: file v{}, CSDL v{}",
          map.version(),
          storedVersion);
      return;
    }
    importer.importParsed(path, map);
  }
}
