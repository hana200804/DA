package vn.signa.catalog.internal;

import java.nio.file.Path;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import vn.signa.shared.config.SignaProperties;

/**
 * Nạp danh mục nhãn khi khởi động nếu {@code signa.catalog.import-on-startup=true} hoặc có tham số
 * {@code --import-labels}. Ứng dụng vẫn chạy tiếp sau khi nạp. File sai thì ngoại lệ làm ứng dụng
 * dừng khởi động. Không chạy ở profile {@code migrate}.
 */
@Component
@Profile("!migrate")
class LabelImportRunner implements ApplicationRunner {

  static final String OPTION = "import-labels";

  private final LabelImporter importer;
  private final CatalogProperties catalog;
  private final SignaProperties signa;

  LabelImportRunner(LabelImporter importer, CatalogProperties catalog, SignaProperties signa) {
    this.importer = importer;
    this.catalog = catalog;
    this.signa = signa;
  }

  @Override
  public void run(ApplicationArguments args) {
    if (!catalog.importOnStartup() && !args.containsOption(OPTION)) return;
    if (signa.labelsPath() == null || signa.labelsPath().isBlank()) {
      throw new IllegalStateException("Cần SIGNA_LABELS_PATH để nạp danh mục nhãn");
    }
    importer.importFrom(Path.of(signa.labelsPath()));
  }
}
