package vn.signa.identity.api;

import java.util.Set;

/**
 * Người dùng của request hiện tại, đặt làm principal của Spring Security. Module khác lấy bằng
 * {@code @AuthenticationPrincipal AuthenticatedUser user}.
 *
 * @param permissions mã quyền theo vai trò (docs/06 §4); kiểm tra quyền, không kiểm tra vai trò
 */
public record AuthenticatedUser(String userId, String role, Set<String> permissions) {

  public AuthenticatedUser {
    permissions = Set.copyOf(permissions);
  }

  public boolean has(String permission) {
    return permissions.contains(permission);
  }
}
