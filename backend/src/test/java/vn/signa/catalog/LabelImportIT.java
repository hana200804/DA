package vn.signa.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import vn.signa.catalog.internal.LabelImporter;
import vn.signa.support.IntegrationTest;

/** Lệnh nạp nhãn trên MySQL thật. Chỉ chạy trên CI (cần Docker). */
class LabelImportIT extends IntegrationTest {

  static final Path LABELS = Path.of("..", "ai", "data", "labels.yaml");

  @Autowired LabelImporter importer;
  @Autowired JdbcClient jdbc;

  @AfterEach
  void restore() {
    importer.importFrom(LABELS);
    jdbc.sql(
            "UPDATE sign_types SET description = NULL, icon_key = NULL, name = label_name,"
                + " active = TRUE WHERE label_id IS NOT NULL")
        .update();
  }

  @Test
  void importTwiceGivesSameRows() {
    importer.importFrom(LABELS);
    List<Map<String, Object>> first = rows();

    LabelImporter.Result second = importer.importFrom(LABELS);

    assertThat(second.typesCreated()).isZero();
    assertThat(second.typesUpdated()).isZero();
    assertThat(rows()).isEqualTo(first);
    assertThat(count("SELECT COUNT(*) FROM sign_types WHERE label_map_version = 1"))
        .isGreaterThanOrEqualTo(46);
    assertThat(count("SELECT COUNT(*) FROM sign_categories")).isGreaterThanOrEqualTo(5);
    assertThat(
            jdbc.sql("SELECT category_code FROM sign_types WHERE code = 'P.102'")
                .query(String.class)
                .single())
        .isEqualTo("PROHIBITORY");
  }

  @Test
  void keepsEditedDescriptionIconAndName() {
    importer.importFrom(LABELS);
    jdbc.sql(
            "UPDATE sign_types SET description = 'Mô tả đã sửa', icon_key = 'P.102.png',"
                + " name = 'Tên đã sửa' WHERE code = 'P.102'")
        .update();
    jdbc.sql("UPDATE sign_types SET name = 'Tên cũ', label_name = 'Tên cũ' WHERE code = 'P.104'")
        .update();

    importer.importFrom(LABELS);

    Map<String, Object> edited =
        jdbc.sql("SELECT name, description, icon_key FROM sign_types WHERE code = 'P.102'")
            .query()
            .singleRow();
    assertThat(edited)
        .containsEntry("name", "Tên đã sửa")
        .containsEntry("description", "Mô tả đã sửa")
        .containsEntry("icon_key", "P.102.png");
    assertThat(
            jdbc.sql("SELECT name FROM sign_types WHERE code = 'P.104'")
                .query(String.class)
                .single())
        .isEqualTo("Cấm xe máy");
  }

  private List<Map<String, Object>> rows() {
    return jdbc.sql("SELECT * FROM sign_types ORDER BY code").query().listOfRows();
  }

  private long count(String sql) {
    return jdbc.sql(sql).query(Long.class).single();
  }
}
