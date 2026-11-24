package vn.signa.catalog.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class LabelsYamlParserTest {

  /** Gradle chạy test trong {@code backend/}. */
  static final Path REAL_FILE = Path.of("..", "ai", "data", "labels.yaml");

  @Test
  void loadsRealLabelMap() {
    LabelMap map = LabelsYamlParser.load(REAL_FILE);

    assertThat(map.version()).isEqualTo(1);
    assertThat(map.categories())
        .extracting(LabelMap.Category::code)
        .containsExactly("PROHIBITORY", "MANDATORY", "WARNING", "AUXILIARY", "OTHER");
    assertThat(map.categories())
        .extracting(LabelMap.Category::sortOrder)
        .containsExactly(1, 2, 3, 4, 5);
    assertThat(map.classes()).hasSize(46);
    assertThat(map.classes()).extracting(LabelMap.LabelClass::id).doesNotHaveDuplicates();
    assertThat(map.classes().getFirst())
        .isEqualTo(
            new LabelMap.LabelClass(
                0,
                "P.102",
                "prohibitory",
                "Cấm đi ngược chiều",
                null,
                map.classes().getFirst().line()));

    Map<String, Integer> speeds =
        map.classes().stream()
            .filter(c -> c.speedKmh() != null)
            .collect(Collectors.toMap(LabelMap.LabelClass::code, LabelMap.LabelClass::speedKmh));
    assertThat(speeds)
        .containsExactlyInAnyOrderEntriesOf(
            Map.of("P.127_40", 40, "P.127_50", 50, "P.127_60", 60, "P.127_80", 80));
  }

  @Test
  void keepsLineNumbers() {
    LabelMap map =
        LabelsYamlParser.parse(
            """
            version: 2
            categories:
              warning: { name: Biển nguy hiểm }
            classes:
              - id: 7
                code: W.201a
                category: warning
                name: Chỗ ngoặt nguy hiểm vòng bên trái
            """,
            "test.yaml");

    assertThat(map.version()).isEqualTo(2);
    assertThat(map.categories().getFirst().line()).isEqualTo(3);
    assertThat(map.classes().getFirst().line()).isEqualTo(5);
    assertThat(map.classes().getFirst().code()).isEqualTo("W.201a");
  }

  @Test
  void reportsStructuralProblemsWithLine() {
    assertThatThrownBy(() ->
            LabelsYamlParser.parse(
                """
                version: one
                categories:
                  warning: { name: Biển nguy hiểm }
                classes:
                  - id: x
                    code: W.201a
                """,
                "test.yaml"))
        .isInstanceOf(LabelsFileException.class)
        .satisfies(e ->
            assertThat(((LabelsFileException) e).problems())
                .containsExactly(
                    "dòng 1: `version` phải là số nguyên",
                    "dòng 5: `id` phải là số nguyên",
                    "dòng 1: thiếu `version`"))
        .hasMessageContaining("test.yaml không hợp lệ");
  }

  @Test
  void rejectsMissingSectionsAndBadSyntax() {
    assertThatThrownBy(() -> LabelsYamlParser.parse("version: 1\n", "t.yaml"))
        .hasMessageContaining("thiếu `categories`")
        .hasMessageContaining("thiếu `classes`");
    assertThatThrownBy(() -> LabelsYamlParser.parse("- a\n- b\n", "t.yaml"))
        .hasMessageContaining("file phải là một mapping");
    assertThatThrownBy(() -> LabelsYamlParser.parse("a: [1, 2\n", "t.yaml"))
        .hasMessageContaining("YAML sai cú pháp");
  }

  @Test
  void refusesArbitraryJavaTypes() {
    assertThatThrownBy(() ->
            LabelsYamlParser.parse(
                "version: 1\ncategories: !!javax.script.ScriptEngineManager []\nclasses: []\n",
                "t.yaml"))
        .isInstanceOf(LabelsFileException.class)
        .hasMessageContaining("Global tag is not allowed");
  }
}
