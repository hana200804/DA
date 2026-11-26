package vn.signa.shared.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Chế độ {@code migrate}: Flyway chạy khi context khởi tạo, xong thì thoát. Chỉ chế độ này giữ mật
 * khẩu {@code signa_migrator}; backend chạy lâu dài chỉ có tài khoản {@code signa_app}, nên dù bị
 * chiếm cũng không sửa được {@code audit_logs} (FR-23).
 */
@Component
@Profile("migrate")
class MigrateAndExit implements ApplicationRunner {

  private static final Logger log = LoggerFactory.getLogger(MigrateAndExit.class);

  private final ConfigurableApplicationContext context;

  MigrateAndExit(ConfigurableApplicationContext context) {
    this.context = context;
  }

  @Override
  public void run(ApplicationArguments args) {
    log.info("Migration xong, thoát chế độ migrate");
    System.exit(SpringApplication.exit(context));
  }
}
