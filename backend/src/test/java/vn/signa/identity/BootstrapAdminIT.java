package vn.signa.identity;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;
import vn.signa.support.Containers;

/**
 * Quản trị viên đầu tiên được tạo khi khởi động và ghi lịch sử {@code USER/ROLE_CHANGED} bằng
 * {@code SYSTEM}. MySQL riêng đảm bảo lúc khởi động chưa có quản trị viên.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@TestPropertySource(
    properties = {
      "signa.identity.bootstrap-admin.email=Bootstrap-Admin@Signa.VN",
      "signa.identity.bootstrap-admin.password=bootstrap-it-secret"
    })
class BootstrapAdminIT {

  @Container static final MySQLContainer MYSQL = Containers.newMysql();

  @DynamicPropertySource
  static void containers(DynamicPropertyRegistry registry) {
    Containers.register(registry, MYSQL);
  }

  @Autowired JdbcTemplate jdbc;

  @Test
  void createsAdminWithSystemAuditEntry() {
    Map<String, Object> admin =
        jdbc.queryForMap(
            "SELECT id, role_code, status FROM users WHERE email = ?", "bootstrap-admin@signa.vn");
    assertThat(admin).containsEntry("role_code", "ADMIN").containsEntry("status", "ACTIVE");

    Map<String, Object> audit =
        jdbc.queryForMap(
            "SELECT action, actor_type, actor_id, after_value FROM audit_logs"
                + " WHERE entity_type = 'USER' AND entity_id = ?",
            admin.get("id"));
    assertThat(audit).containsEntry("action", "ROLE_CHANGED").containsEntry("actor_type", "SYSTEM");
    assertThat(audit.get("actor_id")).isNull();
    assertThat(audit.get("after_value").toString()).contains("ADMIN");
  }
}
