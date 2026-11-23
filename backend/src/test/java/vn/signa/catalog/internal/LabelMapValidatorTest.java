package vn.signa.catalog.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

class LabelMapValidatorTest {

  private static List<String> problems(String yaml) {
    LabelMap map = LabelsYamlParser.parse(yaml, "t.yaml");
    try {
      LabelMapValidator.validate(map, "t.yaml");
    } catch (LabelsFileException e) {
      return e.problems();
    }
    return List.of();
  }

  @Test
  void acceptsValidMap() {
    assertThat(
            problems(
                """
                version: 1
                categories:
                  prohibitory: { name: Biển cấm }
                classes:
                  - id: 0
                    code: P.102
                    category: prohibitory
                    name: Cấm đi ngược chiều
                  - id: 1
                    code: P.127_50
                    category: prohibitory
                    name: Tốc độ tối đa 50 km/h
                    speed_kmh: 50
                """))
        .isEmpty();
  }

  @Test
  void reportsDuplicateIdAndCodeWithBothLines() {
    assertThat(
            problems(
                """
                version: 1
                categories:
                  prohibitory: { name: Biển cấm }
                classes:
                  - id: 0
                    code: P.102
                    category: prohibitory
                    name: A
                  - id: 0
                    code: P.102
                    category: prohibitory
                    name: B
                """))
        .containsExactly(
            "dòng 9: `id` 0 trùng với dòng 5", "dòng 9: `code` P.102 trùng với dòng 5");
  }

  @Test
  void reportsUnknownCategoryAndMissingFields() {
    assertThat(
            problems(
                """
                version: 1
                categories:
                  prohibitory: { name: Biển cấm }
                classes:
                  - id: 0
                    code: P.102
                    category: forbidden
                    name: A
                  - code: P.103a
                    category: prohibitory
                """))
        .containsExactly(
            "dòng 5: nhóm `forbidden` không có trong `categories`",
            "dòng 9: lớp thiếu `id`",
            "dòng 9: lớp thiếu `name`");
  }

  @Test
  void reportsBadValues() {
    assertThat(
            problems(
                """
                version: 0
                categories:
                  1bad: { name: X }
                  noname: {}
                classes:
                  - id: 40000
                    code: P.127_0000000000000000000000000
                    category: noname
                    name: A
                    speed_kmh: -5
                """))
        .containsExactly(
            "dòng 1: `version` phải là số nguyên dương",
            "dòng 3: mã nhóm `1BAD` phải khớp ^[A-Z][A-Z0-9_]{0,19}$",
            "dòng 4: nhóm `noname` thiếu `name`",
            "dòng 6: `id` 40000 phải trong 0..32767",
            "dòng 6: `code` dài quá 30 ký tự",
            "dòng 6: `speed_kmh` phải là số nguyên dương");
  }

  @Test
  void reportsDuplicateCategoryKey() {
    assertThatThrownBy(() ->
            LabelMapValidator.validate(
                LabelsYamlParser.parse(
                    """
                    version: 1
                    categories:
                      other: { name: A }
                      other: { name: B }
                    classes:
                      - { id: 0, code: X, category: other, name: X }
                    """,
                    "t.yaml"),
                "t.yaml"))
        .isInstanceOf(LabelsFileException.class)
        .hasMessageContaining("dòng 4: nhóm `other` trùng với dòng 3");
  }
}
