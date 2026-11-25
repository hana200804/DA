package vn.signa.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Mỗi module chỉ được viết SQL trên bảng của chính nó (docs/03 §5). ArchUnit không thấy tên bảng
 * trong chuỗi SQL, nên test này đọc mã nguồn.
 */
class TableOwnershipTest {

  private static final Path MAIN = Path.of("src/main/java/vn/signa");

  private static final Set<String> ALL_TABLES =
      Modules.TABLES.values().stream().flatMap(Set::stream).collect(Collectors.toSet());

  private static final Pattern SQL_TABLE =
      Pattern.compile(
          "(?i)\\b(?:FROM|JOIN|INTO|UPDATE|TABLE)\\s+`?("
              + String.join("|", ALL_TABLES)
              + ")`?\\b");

  static List<String> violations(String module, String source) {
    Set<String> owned = Modules.TABLES.get(module);
    List<String> found = new ArrayList<>();
    Matcher m = SQL_TABLE.matcher(source);
    while (m.find()) {
      String table = m.group(1).toLowerCase();
      if (!owned.contains(table)) found.add(module + " dùng bảng " + table);
    }
    return found;
  }

  @Test
  void modulesOnlyQueryTheirOwnTables() throws IOException {
    List<String> all = new ArrayList<>();
    for (String module : Modules.NAMES) {
      Path dir = MAIN.resolve(module);
      if (!Files.isDirectory(dir)) continue;
      try (Stream<Path> files = Files.walk(dir)) {
        files
            .filter(p -> p.toString().endsWith(".java"))
            .forEach(p -> violations(module, read(p)).forEach(v -> all.add(v + " trong " + p)));
      }
    }
    assertThat(all).isEmpty();
  }

  @Test
  void detectsForeignTable() {
    String source =
        "String sql = \"\"\"\n SELECT id FROM users u JOIN signs s ON s.id = u.id\"\"\";";
    assertThat(violations("sign", source)).containsExactly("sign dùng bảng users");
    assertThat(violations("identity", source)).containsExactly("identity dùng bảng signs");
    assertThat(violations("sign", "INSERT INTO audit_logs (id) VALUES (1)"))
        .containsExactly("sign dùng bảng audit_logs");
  }

  @Test
  void ownershipCoversEverySchemaTable() {
    assertThat(ALL_TABLES).hasSize(13);
    assertThat(Map.copyOf(Modules.TABLES)).containsKeys(Modules.NAMES.toArray(String[]::new));
  }

  private static String read(Path p) {
    try {
      return Files.readString(p);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
