package vn.signa.identity.internal;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Cấu hình {@code signa.identity}. */
@ConfigurationProperties(prefix = "signa.identity")
record IdentityProperties(BootstrapAdmin bootstrapAdmin) {

  IdentityProperties {
    bootstrapAdmin = bootstrapAdmin == null ? new BootstrapAdmin(null, null, null) : bootstrapAdmin;
  }

  /**
   * Quản trị viên đầu tiên từ {@code SIGNA_BOOTSTRAP_ADMIN_EMAIL}, {@code
   * SIGNA_BOOTSTRAP_ADMIN_PASSWORD}.
   */
  record BootstrapAdmin(String email, String password, String displayName) {

    @Override
    public String toString() {
      return "BootstrapAdmin[email=" + email + ", password=***]";
    }
  }
}
