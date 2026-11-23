package vn.signa.identity.internal;

import java.time.Clock;
import java.util.Collections;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.util.StringUtils;
import vn.signa.audit.api.AuditEntityType;
import vn.signa.audit.api.AuditEntry;
import vn.signa.audit.api.AuditRecorder;

/**
 * Tạo quản trị viên đầu tiên khi khởi động (docs/backend/thiet-ke-trien-khai.md §5.3). Chỉ chạy khi
 * chưa có {@code ADMIN} và có đủ email, mật khẩu; không có mật khẩu mặc định. Email đã thuộc một
 * tài khoản khác thì không nâng quyền tài khoản đó, vì ai cũng có thể đăng ký trước email này.
 */
@Component
class BootstrapAdmin {

  enum Outcome {
    NOT_CONFIGURED,
    ADMIN_EXISTS,
    EMAIL_TAKEN,
    CREATED
  }

  static final String DEFAULT_DISPLAY_NAME = "Quản trị viên";
  private static final Logger log = LoggerFactory.getLogger(BootstrapAdmin.class);

  private final IdentityProperties.BootstrapAdmin config;
  private final UserRepository users;
  private final PasswordEncoder encoder;
  private final AuditRecorder audit;
  private final TransactionOperations tx;
  private final Clock clock;

  BootstrapAdmin(
      IdentityProperties properties,
      UserRepository users,
      PasswordEncoder encoder,
      AuditRecorder audit,
      TransactionOperations tx,
      Clock clock) {
    this.config = properties.bootstrapAdmin();
    this.users = users;
    this.encoder = encoder;
    this.audit = audit;
    this.tx = tx;
    this.clock = clock;
  }

  Outcome run() {
    String email = config.email() == null ? null : config.email().trim().toLowerCase(Locale.ROOT);
    String password = config.password();
    if (!StringUtils.hasText(email) || !StringUtils.hasLength(password)) {
      if (StringUtils.hasText(email) || StringUtils.hasLength(password)) {
        log.warn(
            "Cần cả SIGNA_BOOTSTRAP_ADMIN_EMAIL và SIGNA_BOOTSTRAP_ADMIN_PASSWORD; bỏ qua tạo"
                + " quản trị viên đầu tiên");
      }
      return Outcome.NOT_CONFIGURED;
    }
    if (users.existsWithRole("ADMIN")) {
      return Outcome.ADMIN_EXISTS;
    }
    if (email.length() > 255 || email.indexOf('@') < 1) {
      throw new IllegalStateException("SIGNA_BOOTSTRAP_ADMIN_EMAIL không hợp lệ");
    }
    String problem = PasswordPolicy.problem(password);
    if (problem != null) {
      throw new IllegalStateException("SIGNA_BOOTSTRAP_ADMIN_PASSWORD " + problem);
    }
    if (users.findByEmail(email).isPresent()) {
      log.warn("Email quản trị viên đầu tiên đã thuộc một tài khoản khác; không nâng quyền");
      return Outcome.EMAIL_TAKEN;
    }
    String displayName =
        StringUtils.hasText(config.displayName())
            ? config.displayName().trim()
            : DEFAULT_DISPLAY_NAME;
    String id = UUID.randomUUID().toString();
    UserRecord admin =
        new UserRecord(
            id, email, encoder.encode(password), displayName, "ADMIN", UserStatus.ACTIVE);
    try {
      tx.executeWithoutResult(status -> {
        users.insert(admin, clock.instant());
        audit.record(
            AuditEntry.bySystem(
                AuditEntityType.USER,
                id,
                "ROLE_CHANGED",
                Collections.singletonMap("role", null),
                Map.of("role", "ADMIN"),
                "Quản trị viên đầu tiên khi khởi động"));
      });
    } catch (DuplicateKeyException e) {
      // Một instance khác vừa tạo cùng email.
      return Outcome.EMAIL_TAKEN;
    }
    log.info("Đã tạo quản trị viên đầu tiên userId={}", id);
    return Outcome.CREATED;
  }
}
