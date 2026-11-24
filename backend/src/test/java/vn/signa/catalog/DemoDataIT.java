package vn.signa.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import vn.signa.catalog.internal.LabelImporter;
import vn.signa.support.Containers;
import vn.signa.support.IntegrationTest;

/**
 * {@code db/demo/R__demo_data.sql} chạy được (hai lần) trên lược đồ thật, và lệnh nạp nhãn chạy sau
 * đó không đổi gì. Chỉ chạy trên CI. Biển mẫu bị xoá sau test để không ảnh hưởng IT khác.
 */
class DemoDataIT extends IntegrationTest {

  private static final String DEMO_IDS = "id LIKE '00000000-0000-4000-8000-%'";

  @Autowired JdbcClient jdbc;
  @Autowired LabelImporter importer;

  private final DriverManagerDataSource migrator =
      new DriverManagerDataSource(
          Containers.MYSQL.getJdbcUrl(), Containers.MIGRATOR_USER, Containers.MIGRATOR_PASSWORD);

  @AfterEach
  void removeDemoSigns() {
    JdbcClient.create(migrator).sql("DELETE FROM signs WHERE " + DEMO_IDS).update();
  }

  @Test
  void demoDataLoadsAroundHanoi() {
    // Chạy thẳng file thay vì Flyway để không thêm dòng R__ vào lịch sử migration dùng chung:
    // context sau chỉ có classpath:db/migration sẽ báo migration thiếu. Chạy hai lần để chứng minh
    // chạy lại được.
    ResourceDatabasePopulator demo =
        new ResourceDatabasePopulator(new ClassPathResource("db/demo/R__demo_data.sql"));
    demo.execute(migrator);
    demo.execute(migrator);

    Map<String, Long> byStatus =
        jdbc.sql("SELECT status FROM signs WHERE " + DEMO_IDS).query(String.class).list().stream()
            .collect(Collectors.groupingBy(s -> s, Collectors.counting()));
    assertThat(byStatus)
        .containsExactlyInAnyOrderEntriesOf(
            Map.of("APPROVED", 18L, "UNDER_REVIEW", 6L, "PENDING", 6L));
    assertThat(
            jdbc.sql(
                    "SELECT COUNT(*) FROM signs WHERE "
                        + DEMO_IDS
                        + " AND ST_Latitude(location) BETWEEN 21.02 AND 21.04"
                        + " AND ST_Longitude(location) BETWEEN 105.80 AND 105.85")
                .query(Long.class)
                .single())
        .isEqualTo(30L);

    LabelImporter.Result result = importer.importFrom(LabelImportIT.LABELS);
    assertThat(result.typesUpdated()).isZero();
  }
}
