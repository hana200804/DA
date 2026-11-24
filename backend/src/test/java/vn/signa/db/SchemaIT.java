package vn.signa.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.SQLException;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import vn.signa.support.IntegrationTest;

/**
 * Lược đồ sau migration và quyền của tài khoản ứng dụng (docs/06 §4, §8; IT-B05). {@link
 * JdbcTemplate} của ứng dụng kết nối bằng {@code signa_app}.
 */
class SchemaIT extends IntegrationTest {

  /** {@code ER_TABLEACCESS_DENIED_ERROR}: "... command denied to user ... for table ...". */
  private static final int TABLE_ACCESS_DENIED = 1142;

  @Autowired Flyway flyway;
  @Autowired JdbcTemplate jdbc;

  @Test
  void migrationsApplied() {
    var info = flyway.info();
    assertThat(info.pending()).isEmpty();
    assertThat(info.applied())
        .extracting(MigrationInfo::getVersion)
        .contains(
            MigrationVersion.fromVersion("1"),
            MigrationVersion.fromVersion("2"),
            MigrationVersion.fromVersion("3"),
            MigrationVersion.fromVersion("20"),
            MigrationVersion.fromVersion("50"),
            MigrationVersion.fromVersion("51"),
            MigrationVersion.fromVersion("70"),
            MigrationVersion.fromVersion("90"),
            MigrationVersion.fromVersion("100"),
            MigrationVersion.fromVersion("110"),
            MigrationVersion.fromVersion("111"));
  }

  @Test
  void connectsAsAppAccount() {
    assertThat(jdbc.queryForObject("SELECT CURRENT_USER()", String.class)).isEqualTo("signa_app@%");
  }

  @Test
  void rolesAndPermissionMatrixMatchDocs() {
    var user = Set.of("submission:create", "sign:rate", "report:create");
    var moderator = new TreeSet<>(user);
    moderator.addAll(
        Set.of(
            "review:decide",
            "report:resolve",
            "sign:edit",
            "audit:read",
            "stats:read",
            "recognition:compare"));
    var admin = new TreeSet<>(moderator);
    admin.addAll(Set.of("catalog:manage", "user:manage"));

    assertThat(jdbc.queryForList("SELECT code FROM roles", String.class))
        .containsExactlyInAnyOrder("USER", "MODERATOR", "ADMIN");
    assertThat(jdbc.queryForList("SELECT code FROM permissions", String.class))
        .hasSize(11)
        .containsExactlyInAnyOrderElementsOf(admin);

    Map<String, Set<String>> matrix = new TreeMap<>();
    jdbc.query(
        "SELECT role_code, permission_code FROM role_permissions",
        rs -> {
          matrix
              .computeIfAbsent(rs.getString("role_code"), k -> new TreeSet<>())
              .add(rs.getString("permission_code"));
        });
    assertThat(matrix)
        .containsOnlyKeys("USER", "MODERATOR", "ADMIN")
        .containsEntry("USER", new TreeSet<>(user))
        .containsEntry("MODERATOR", moderator)
        .containsEntry("ADMIN", admin);
  }

  @Test
  void appAccountCanOnlyAppendAuditLogs() {
    String entityId = UUID.randomUUID().toString();
    int inserted =
        jdbc.update(
            "INSERT INTO audit_logs (entity_type, entity_id, action, actor_type, created_at)"
                + " VALUES ('SIGN', ?, 'CREATED', 'SYSTEM', UTC_TIMESTAMP(3))",
            entityId);
    assertThat(inserted).isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM audit_logs WHERE entity_id = ?", Integer.class, entityId))
        .isEqualTo(1);

    assertDenied(
        () -> jdbc.update("UPDATE audit_logs SET reason = 'sửa' WHERE entity_id = ?", entityId));
    assertDenied(() -> jdbc.update("DELETE FROM audit_logs WHERE entity_id = ?", entityId));
    assertDenied(() -> jdbc.execute("DROP TABLE audit_logs"));

    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM audit_logs WHERE entity_id = ? AND reason IS NULL",
                Integer.class,
                entityId))
        .isEqualTo(1);
  }

  @Test
  void appAccountReadsAndWritesSigns() {
    String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase();
    String category = "IT" + suffix;
    String type = "IT." + suffix;
    String signId = UUID.randomUUID().toString();

    jdbc.update(
        "INSERT INTO sign_categories (code, name, sort_order) VALUES (?, 'Nhóm thử', 99)",
        category);
    jdbc.update(
        "INSERT INTO sign_types (code, category_code, name, created_at, updated_at)"
            + " VALUES (?, ?, 'Loại thử', UTC_TIMESTAMP(3), UTC_TIMESTAMP(3))",
        type,
        category);
    jdbc.update(
        "INSERT INTO signs (id, sign_type_code, category_code, location, status, confidence,"
            + " created_at, updated_at) VALUES (?, ?, ?,"
            + " ST_GeomFromText('POINT(105.8048 21.0285)', 4326, 'axis-order=long-lat'),"
            + " 'PENDING', 0.5000, UTC_TIMESTAMP(3), UTC_TIMESTAMP(3))",
        signId,
        type,
        category);

    int updated =
        jdbc.update(
            "UPDATE signs SET status = 'APPROVED', version = version + 1"
                + " WHERE id = ? AND version = 0",
            signId);
    assertThat(updated).isEqualTo(1);

    var row =
        jdbc.queryForMap(
            "SELECT status, version, ST_Latitude(location) AS lat, ST_Longitude(location) AS lng"
                + " FROM signs WHERE id = ?",
            signId);
    assertThat(row.get("status")).isEqualTo("APPROVED");
    assertThat(((Number) row.get("version")).intValue()).isEqualTo(1);
    assertThat(((Number) row.get("lat")).doubleValue()).isEqualTo(21.0285);
    assertThat(((Number) row.get("lng")).doubleValue()).isEqualTo(105.8048);
  }

  private static void assertDenied(ThrowingCallable call) {
    assertThatThrownBy(call)
        .isInstanceOf(DataAccessException.class)
        .satisfies(e -> assertThat(sqlErrorCode(e)).isEqualTo(TABLE_ACCESS_DENIED));
  }

  private static int sqlErrorCode(Throwable error) {
    for (Throwable t = error; t != null; t = t.getCause()) {
      if (t instanceof SQLException sql) {
        return sql.getErrorCode();
      }
    }
    return -1;
  }
}
