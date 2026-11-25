package vn.signa.identity.internal;

import java.util.Collection;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import vn.signa.identity.api.UserDirectory;

/** Tên hiển thị của nhiều tài khoản trong một truy vấn. */
@Component
class JdbcUserDirectory implements UserDirectory {

  private final JdbcClient jdbc;

  JdbcUserDirectory(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public Map<String, String> displayNames(Collection<String> ids) {
    Set<String> distinct =
        ids.stream().filter(Objects::nonNull).collect(Collectors.toUnmodifiableSet());
    if (distinct.isEmpty()) {
      return Map.of();
    }
    return jdbc
        .sql("SELECT id, display_name FROM users WHERE id IN (:ids)")
        .param("ids", distinct)
        .query((rs, row) -> Map.entry(rs.getString("id"), rs.getString("display_name")))
        .list()
        .stream()
        .collect(Collectors.toUnmodifiableMap(Map.Entry::getKey, Map.Entry::getValue));
  }
}
