package vn.signa.identity;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import vn.signa.support.IntegrationTest;

/**
 * Quản trị viên đầu tiên được tạo khi khởi động và ghi lịch sử {@code USER/ROLE_CHANGED} bằng
 * {@code SYSTEM}. Thuộc tính riêng nên lớp này có context riêng.
 */
@TestPropertySource(
    properties = {
      "signa.identity.bootstrap-admin.email=Bootstrap-Admin@Signa.VN",
      "signa.identity.bootstrap-admin.password=bootstrap-it-secret"
    })
class BootstrapAdminIT extends IntegrationTest {

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
