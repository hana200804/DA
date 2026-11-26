package vn.signa.db;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.JdbcTemplateAutoConfiguration;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;
import vn.signa.support.Containers;

/** A9: nạp demo ở schema cũ rồi nâng schema bình thường trên cùng volume. */
@Testcontainers
class DemoMigrationIT {

  @Container static final MySQLContainer MYSQL = Containers.newMysql();

  @Test
  void normalMigrationUpgradesDatabaseWithDemoHistory() {
    runner("migrate,demo")
        .withPropertyValues("spring.flyway.target=90")
        .run(context -> {
          assertThat(context).hasNotFailed();
          var applied = context.getBean(Flyway.class).info().applied();
          assertThat(Arrays.stream(applied).map(MigrationInfo::getScript))
              .contains("R__demo_data.sql");
          assertThat(demoSigns(context.getBean(JdbcTemplate.class))).isEqualTo(30);
        });

    runner("migrate")
        .run(context -> {
          assertThat(context).hasNotFailed();
          var info = context.getBean(Flyway.class).info();
          assertThat(info.pending()).isEmpty();
          assertThat(info.applied())
              .extracting(MigrationInfo::getVersion)
              .contains(MigrationVersion.fromVersion("100"));
          assertThat(demoSigns(context.getBean(JdbcTemplate.class))).isEqualTo(30);
        });
  }

  private static ApplicationContextRunner runner(String profiles) {
    // Nạp cấu hình thật và Flyway, tránh ApplicationRunner migrate vốn gọi System.exit.
    return new ApplicationContextRunner()
        .withInitializer(new ConfigDataApplicationContextInitializer())
        .withConfiguration(
            AutoConfigurations.of(
                DataSourceAutoConfiguration.class,
                JdbcTemplateAutoConfiguration.class,
                FlywayAutoConfiguration.class))
        .withPropertyValues(
            "spring.profiles.active=" + profiles,
            "SIGNA_DB_URL=" + MYSQL.getJdbcUrl(),
            "SIGNA_DB_APP_USER=" + Containers.APP_USER,
            "SIGNA_DB_APP_PASSWORD=" + Containers.APP_PASSWORD,
            "SIGNA_DB_MIGRATOR_USER=" + Containers.MIGRATOR_USER,
            "SIGNA_DB_MIGRATOR_PASSWORD=" + Containers.MIGRATOR_PASSWORD);
  }

  private static int demoSigns(JdbcTemplate jdbc) {
    return jdbc.queryForObject(
        "SELECT COUNT(*) FROM signs WHERE id LIKE '00000000-0000-4000-8000-%'", Integer.class);
  }
}
