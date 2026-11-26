package vn.signa.catalog.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;
import vn.signa.shared.config.SignaProperties;
import vn.signa.support.Containers;

/** Kiểm dữ liệu demo và nạp bù danh mục trên MySQL riêng, cùng quyền ứng dụng như Compose. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class DemoDataIT {

  private static final String DEMO_IDS = "id LIKE '00000000-0000-4000-8000-%'";

  @Container static final MySQLContainer MYSQL = Containers.newMysql();

  @DynamicPropertySource
  static void containers(DynamicPropertyRegistry registry) {
    Containers.register(registry, MYSQL);
  }

  @Autowired JdbcClient jdbc;
  @Autowired LabelImporter importer;

  @Test
  void startupCompletesDemoCatalogAndPreservesEditedType() {
    var migrator =
        new DriverManagerDataSource(
            MYSQL.getJdbcUrl(), Containers.MIGRATOR_USER, Containers.MIGRATOR_PASSWORD);
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

    assertThat(labelCount()).isEqualTo(14);
    jdbc.sql(
            "UPDATE sign_types SET category_code = 'OTHER', speed_kmh = 55 WHERE code = 'P.127_50'")
        .update();
    long generation = generation();
    var runner =
        new LabelImportRunner(
            importer,
            new CatalogProperties(true, Duration.ofHours(24)),
            new SignaProperties(null, null, Path.of("..", "ai", "data", "labels.yaml").toString()));

    runner.run(new DefaultApplicationArguments());

    assertThat(labelCount()).isEqualTo(46);
    assertThat(
            jdbc.sql("SELECT category_code, speed_kmh FROM sign_types WHERE code = 'P.127_50'")
                .query()
                .singleRow())
        .containsEntry("category_code", "OTHER")
        .containsEntry("speed_kmh", 55);
    assertThat(
            jdbc.sql(
                    "SELECT COUNT(*) FROM audit_logs WHERE entity_type = 'SIGN_TYPE'"
                        + " AND entity_id = 'W.201a' AND action = 'CREATED' AND actor_type = 'SYSTEM'")
                .query(Long.class)
                .single())
        .isEqualTo(1);
    assertThat(generation()).isEqualTo(generation + 1);

    runner.run(new DefaultApplicationArguments());

    assertThat(generation()).isEqualTo(generation + 1);
  }

  private long labelCount() {
    return jdbc.sql("SELECT COUNT(*) FROM sign_types WHERE label_map_version = 1")
        .query(Long.class)
        .single();
  }

  private long generation() {
    return jdbc.sql("SELECT generation FROM catalog_generation WHERE id = 1")
        .query(Long.class)
        .single();
  }
}
