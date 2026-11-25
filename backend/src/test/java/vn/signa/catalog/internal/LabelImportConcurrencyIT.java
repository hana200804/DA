package vn.signa.catalog.internal;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import vn.signa.support.IntegrationTest;

class LabelImportConcurrencyIT extends IntegrationTest {

  @Autowired LabelImporter importer;
  @Autowired JdbcClient jdbc;

  @Test
  void concurrentStartupKeepsNewerLabelVersion(@TempDir Path directory) throws Exception {
    Path original = Path.of("..", "ai", "data", "labels.yaml");
    String yaml = Files.readString(original);
    Path v2 =
        Files.writeString(directory.resolve("v2.yaml"), yaml.replace("version: 1", "version: 2"));
    Path v3 =
        Files.writeString(directory.resolve("v3.yaml"), yaml.replace("version: 1", "version: 3"));
    var start = new CyclicBarrier(2);

    try (var pool = Executors.newFixedThreadPool(2)) {
      var older =
          pool.submit(() -> {
            start.await(10, SECONDS);
            importer.importOnStartup(v2);
            return null;
          });
      var newer =
          pool.submit(() -> {
            start.await(10, SECONDS);
            importer.importOnStartup(v3);
            return null;
          });
      older.get(30, SECONDS);
      newer.get(30, SECONDS);
      assertThat(
              jdbc.sql("SELECT COUNT(*) FROM sign_types WHERE label_map_version = 3")
                  .query(Long.class)
                  .single())
          .isEqualTo(46);
    } finally {
      importer.importFrom(original);
    }
  }
}
