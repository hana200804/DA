package vn.signa.identity.internal;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** SQL trên bảng {@code users}. Email đã được chuẩn hoá chữ thường trước khi tới đây. */
@Repository
class UserRepository {

  private static final String COLUMNS = "id, email, password_hash, display_name, role_code, status";

  private final JdbcClient jdbc;

  UserRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  Optional<UserRecord> findByEmail(String email) {
    return jdbc.sql("SELECT " + COLUMNS + " FROM users WHERE email = :email")
        .param("email", email)
        .query(UserRepository::map)
        .optional();
  }

  Optional<UserRecord> findById(String id) {
    return jdbc.sql("SELECT " + COLUMNS + " FROM users WHERE id = :id")
        .param("id", id)
        .query(UserRepository::map)
        .optional();
  }

  boolean existsWithRole(String role) {
    return jdbc.sql("SELECT EXISTS (SELECT 1 FROM users WHERE role_code = :role)")
        .param("role", role)
        .query(Boolean.class)
        .single();
  }

  /** Ném {@link org.springframework.dao.DuplicateKeyException} khi trùng email. */
  void insert(UserRecord user, Instant now) {
    jdbc.sql(
            """
            INSERT INTO users (id, email, password_hash, display_name, role_code, status,
                               created_at, updated_at)
            VALUES (:id, :email, :hash, :name, :role, :status, :now, :now)
            """)
        .param("id", user.id())
        .param("email", user.email())
        .param("hash", user.passwordHash())
        .param("name", user.displayName())
        .param("role", user.role())
        .param("status", user.status().name())
        .param("now", Timestamp.from(now))
        .update();
  }

  boolean updateDisplayName(String id, String displayName, Instant now) {
    return jdbc.sql("UPDATE users SET display_name = :name, updated_at = :now WHERE id = :id")
            .param("name", displayName)
            .param("now", Timestamp.from(now))
            .param("id", id)
            .update()
        == 1;
  }

  boolean updatePasswordHash(String id, String hash, Instant now) {
    return jdbc.sql("UPDATE users SET password_hash = :hash, updated_at = :now WHERE id = :id")
            .param("hash", hash)
            .param("now", Timestamp.from(now))
            .param("id", id)
            .update()
        == 1;
  }

  void touchLastLogin(String id, Instant now) {
    jdbc.sql("UPDATE users SET last_login_at = :now WHERE id = :id")
        .param("now", Timestamp.from(now))
        .param("id", id)
        .update();
  }

  private static UserRecord map(ResultSet rs, int row) throws SQLException {
    return new UserRecord(
        rs.getString("id"),
        rs.getString("email"),
        rs.getString("password_hash"),
        rs.getString("display_name"),
        rs.getString("role_code"),
        UserStatus.valueOf(rs.getString("status")));
  }
}
