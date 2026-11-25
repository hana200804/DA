package vn.signa.identity.internal;

import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Bảng quyền theo vai trò, nạp từ {@code role_permissions} khi khởi động (docs/03 §6.6). Đổi phân
 * quyền chỉ cần sửa dữ liệu rồi khởi động lại.
 */
@Component
public class RolePermissions {

  private final JdbcClient jdbc;
  private volatile Map<String, Set<String>> byRole;

  RolePermissions(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  void load() {
    record Row(String role, String permission) {}
    var rows =
        jdbc.sql("SELECT role_code, permission_code FROM role_permissions")
            .query((rs, n) -> new Row(rs.getString(1), rs.getString(2)))
            .list();
    replace(
        rows.stream()
            .collect(
                Collectors.groupingBy(
                    Row::role, Collectors.mapping(Row::permission, Collectors.toSet()))));
  }

  void replace(Map<String, Set<String>> matrix) {
    Map<String, Set<String>> copy = new TreeMap<>();
    matrix.forEach((role, perms) -> copy.put(role, Set.copyOf(new TreeSet<>(perms))));
    this.byRole = Map.copyOf(copy);
  }

  /** Quyền của vai trò; vai trò lạ thì rỗng. */
  public Set<String> of(String role) {
    Map<String, Set<String>> current = byRole;
    if (current == null) {
      throw new IllegalStateException("Bảng quyền chưa được nạp");
    }
    return current.getOrDefault(role, Set.of());
  }
}
