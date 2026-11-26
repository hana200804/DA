package vn.signa.identity.internal;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** SQL trên bảng {@code users}. Email đã được chuẩn hoá chữ thường trước khi tới đây. */
@Repository
class UserRepository {

  private static final String COLUMNS =
      "id, email, password_hash, display_name, role_code, status, credential_version";

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

  record AuthState(String role, UserStatus status, int credentialVersion, String retainedSession) {}

  Optional<AuthState> findAuthState(String id) {
    return jdbc.sql(
            "SELECT role_code, status, credential_version, retained_session_hash FROM users WHERE id = :id")
        .param("id", id)
        .query((rs, row) ->
            new AuthState(
                rs.getString(1),
                UserStatus.valueOf(rs.getString(2)),
                rs.getInt(3),
                rs.getString(4)))
        .optional();
  }

  /** Đọc trạng thái mới nhất và khoá dòng tới hết giao dịch. */
  Optional<UserRecord> findByIdForUpdate(String id) {
    return jdbc.sql("SELECT " + COLUMNS + " FROM users WHERE id = :id FOR UPDATE")
        .param("id", id)
        .query(UserRepository::map)
        .optional();
  }

  /**
   * Khoá mọi quản trị viên {@code ACTIVE} tới hết giao dịch và trả id của họ. Mọi thay đổi vai trò,
   * trạng thái đều khoá tập này trước, nên hai quản trị viên khoá nhau cùng lúc phải chạy lần lượt
   * và luật "còn ít nhất một quản trị viên" thấy số liệu đúng.
   */
  List<String> lockActiveAdmins() {
    return jdbc.sql(
            "SELECT id FROM users WHERE role_code = 'ADMIN' AND status = 'ACTIVE' ORDER BY id"
                + " FOR UPDATE")
        .query(String.class)
        .list();
  }

  boolean updateRoleAndStatus(
      String id, String role, UserStatus status, boolean revoke, Instant now) {
    return jdbc.sql(
                """
                UPDATE users SET role_code = :role, status = :status, updated_at = :now,
                                 credential_version = credential_version + :increment,
                                 retained_session_hash = IF(:increment = 1, NULL, retained_session_hash)
                WHERE id = :id
                """)
            .param("role", role)
            .param("status", status.name())
            .param("increment", revoke ? 1 : 0)
            .param("now", Timestamp.from(now))
            .param("id", id)
            .update()
        == 1;
  }

  long countManaged(UserSearch search) {
    Where where = Where.of(search);
    return jdbc.sql("SELECT COUNT(*) FROM users" + where.sql())
        .params(where.params())
        .query(Long.class)
        .single();
  }

  List<ManagedUser> findManaged(UserSearch search) {
    Where where = Where.of(search);
    return jdbc.sql(
            "SELECT id, email, display_name, role_code, status, created_at, last_login_at"
                + " FROM users"
                + where.sql()
                + " ORDER BY created_at DESC, id DESC LIMIT :limit OFFSET :offset")
        .params(where.params())
        .param("limit", search.size())
        .param("offset", search.offset())
        .query((rs, row) -> {
          Timestamp lastLogin = rs.getTimestamp("last_login_at");
          return new ManagedUser(
              rs.getString("id"),
              rs.getString("email"),
              rs.getString("display_name"),
              rs.getString("role_code"),
              UserStatus.valueOf(rs.getString("status")),
              rs.getTimestamp("created_at").toInstant(),
              lastLogin == null ? null : lastLogin.toInstant());
        })
        .list();
  }

  /** Mệnh đề {@code WHERE} của danh sách người dùng, ghép từ các điều kiện có mặt. */
  record Where(String sql, Map<String, Object> params) {

    static Where of(UserSearch search) {
      List<String> conditions = new ArrayList<>();
      Map<String, Object> params = new LinkedHashMap<>();
      String email = LikePattern.contains(search.q());
      if (email != null) {
        conditions.add("email LIKE :email ESCAPE '" + LikePattern.ESCAPE + "'");
        params.put("email", email);
      }
      if (search.role() != null) {
        conditions.add("role_code = :role");
        params.put("role", search.role());
      }
      if (search.status() != null) {
        conditions.add("status = :status");
        params.put("status", search.status().name());
      }
      String sql = conditions.isEmpty() ? "" : " WHERE " + String.join(" AND ", conditions);
      return new Where(sql, params);
    }
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

  boolean updateActivePasswordHash(
      String id, int previousVersion, String hash, String retainedSession, Instant now) {
    return jdbc.sql(
                """
                UPDATE users SET password_hash = :hash, updated_at = :now,
                                 credential_version = credential_version + 1, retained_session_hash = :retainedSession
                WHERE id = :id AND status = 'ACTIVE' AND credential_version = :previousVersion
                """)
            .param("hash", hash)
            .param("previousVersion", previousVersion)
            .param("retainedSession", retainedSession, Types.CHAR)
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
        UserStatus.valueOf(rs.getString("status")),
        rs.getInt("credential_version"));
  }
}
