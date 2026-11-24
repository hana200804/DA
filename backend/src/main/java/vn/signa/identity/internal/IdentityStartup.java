package vn.signa.identity.internal;

import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Nạp bảng quyền rồi tạo quản trị viên đầu tiên nếu cần, trước khi server nhận request. Bỏ qua ở
 * chế độ {@code migrate}, vì chế độ đó không có mật khẩu {@code signa_app}.
 */
@Component
@Profile("!migrate")
class IdentityStartup implements SmartInitializingSingleton {

  private final RolePermissions rolePermissions;
  private final BootstrapAdmin bootstrapAdmin;

  IdentityStartup(RolePermissions rolePermissions, BootstrapAdmin bootstrapAdmin) {
    this.rolePermissions = rolePermissions;
    this.bootstrapAdmin = bootstrapAdmin;
  }

  @Override
  public void afterSingletonsInstantiated() {
    rolePermissions.load();
    bootstrapAdmin.run();
  }
}
