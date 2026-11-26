package vn.signa.architecture;

import java.util.List;
import java.util.Map;
import java.util.Set;

/** Danh sách module và bảng mỗi module sở hữu, theo docs/03 §5 và docs/06. */
final class Modules {

  static final List<String> NAMES = List.of("identity", "catalog", "sign", "recognition", "audit");

  static final Map<String, Set<String>> TABLES =
      Map.of(
          "identity", Set.of("users", "roles", "permissions", "role_permissions"),
          "catalog", Set.of("sign_categories", "sign_types", "catalog_generation"),
          "sign",
              Set.of("signs", "sign_code_weights", "submissions", "sign_ratings", "sign_reports"),
          "recognition", Set.of(),
          "audit", Set.of("audit_logs"));

  private Modules() {}
}
