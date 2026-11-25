package vn.signa.identity.internal;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import vn.signa.identity.internal.SessionService.IssuedSession;
import vn.signa.shared.error.ApiException;
import vn.signa.shared.error.ErrorCode;

/** Đăng ký, đăng nhập, hồ sơ và đổi mật khẩu (FR-01, FR-30). */
@Service
public class AccountService {

  /** Quyền bắt buộc để đăng nhập web quản trị (FR-30). */
  public static final String WEB_PERMISSION = "review:decide";

  static final String BAD_CREDENTIALS = "Email hoặc mật khẩu không đúng";

  private static final Logger log = LoggerFactory.getLogger(AccountService.class);

  /** Hồ sơ trả cho {@code /me} và sau đăng nhập. */
  public record Profile(
      String id, String email, String displayName, String role, List<String> permissions) {}

  public record LoginResult(String token, Instant expiresAt, Profile user) {}

  private final UserRepository users;
  private final PasswordEncoder encoder;
  private final SessionService sessions;
  private final RolePermissions rolePermissions;
  private final LoginRateLimiter limiter;
  private final TransactionTemplate transactions;
  private final Clock clock;

  /**
   * Hash giả để email không tồn tại cũng tốn một lần BCrypt, tránh đoán email qua thời gian phản
   * hồi.
   */
  private final String dummyHash;

  AccountService(
      UserRepository users,
      PasswordEncoder encoder,
      SessionService sessions,
      RolePermissions rolePermissions,
      LoginRateLimiter limiter,
      TransactionTemplate transactions,
      Clock clock) {
    this.users = users;
    this.encoder = encoder;
    this.sessions = sessions;
    this.rolePermissions = rolePermissions;
    this.limiter = limiter;
    this.transactions = transactions;
    this.clock = clock;
    this.dummyHash = encoder.encode(UUID.randomUUID().toString());
  }

  /** Email đã chuẩn hoá, mật khẩu và tên đã qua kiểm tra ở tầng web. */
  public Profile register(String email, String password, String displayName) {
    UserRecord user =
        new UserRecord(
            UUID.randomUUID().toString(),
            email,
            encoder.encode(password),
            displayName,
            "USER",
            UserStatus.ACTIVE);
    try {
      users.insert(user, clock.instant());
    } catch (DuplicateKeyException e) {
      throw new ApiException(ErrorCode.CONFLICT, "Email đã được đăng ký");
    }
    log.info("Đăng ký tài khoản userId={}", user.id());
    return profile(user);
  }

  public LoginResult login(String email, String password, Client client, String ip) {
    UserRecord user = users.findByEmail(email).orElse(null);
    // Các chuỗi email được MySQL coi là tương đương cùng dùng email chuẩn trong bản ghi.
    String limitKey = LoginRateLimiter.loginKey(user == null ? email : user.email(), ip);
    limiter.acquire(limitKey);
    boolean matches = matches(password, user == null ? dummyHash : user.passwordHash());
    if (user == null || !matches) {
      throw new ApiException(ErrorCode.UNAUTHENTICATED, BAD_CREDENTIALS);
    }
    limiter.reset(limitKey);
    LoginResult result =
        transactions.execute(status -> {
          // Đọc lại sau BCrypt và giữ khoá đến khi ghi xong phiên.
          UserRecord current =
              users
                  .findByIdForUpdate(user.id())
                  .filter(row -> row.credentialVersion() == user.credentialVersion())
                  .orElseThrow(() -> new ApiException(ErrorCode.UNAUTHENTICATED, BAD_CREDENTIALS));
          if (current.status() == UserStatus.LOCKED) {
            throw new ApiException(ErrorCode.FORBIDDEN, "Tài khoản đã bị khoá");
          }
          Set<String> permissions = rolePermissions.of(current.role());
          if (client == Client.WEB && !permissions.contains(WEB_PERMISSION)) {
            throw new ApiException(
                ErrorCode.FORBIDDEN, "Chỉ kiểm duyệt viên và quản trị viên được vào web quản trị");
          }
          users.touchLastLogin(current.id(), clock.instant());
          IssuedSession session = sessions.issue(current.id(), current.credentialVersion(), client);
          return new LoginResult(session.token(), session.expiresAt(), profile(current));
        });
    log.info("Đăng nhập userId={} client={}", user.id(), client);
    return result;
  }

  public void logout(String sessionId) {
    sessions.revoke(sessionId);
  }

  public Profile profile(String userId) {
    return profile(load(userId));
  }

  public Profile rename(String userId, String displayName) {
    if (!users.updateDisplayName(userId, displayName, clock.instant())) {
      throw new ApiException(ErrorCode.UNAUTHENTICATED);
    }
    return profile(userId);
  }

  /**
   * Sai mật khẩu hiện tại thì {@code 422}, quá 10 lần trong 15 phút thì {@code 429}; xong thì thu
   * hồi mọi phiên khác (FR-01, IT-B10).
   */
  public void changePassword(
      String userId, String currentSessionId, String currentPassword, String newPassword) {
    UserRecord user = load(userId);
    String limitKey = LoginRateLimiter.passwordKey(userId);
    limiter.acquire(limitKey);
    if (!matches(currentPassword, user.passwordHash())) {
      throw new ApiException(ErrorCode.BUSINESS_RULE, "Mật khẩu hiện tại không đúng");
    }
    limiter.reset(limitKey);
    String hash = encoder.encode(newPassword);
    transactions.executeWithoutResult(status -> {
      if (!users.updateActivePasswordHash(
          userId, user.credentialVersion(), hash, currentSessionId, clock.instant())) {
        throw new ApiException(ErrorCode.BUSINESS_RULE, "Mật khẩu hiện tại không đúng");
      }
      sessions.keepAfterCommit(userId, user.credentialVersion() + 1, currentSessionId);
    });
    log.info("Đổi mật khẩu userId={}, đã thu hồi các phiên khác", userId);
  }

  private UserRecord load(String userId) {
    return users.findById(userId).orElseThrow(() -> new ApiException(ErrorCode.UNAUTHENTICATED));
  }

  private boolean matches(String raw, String hash) {
    if (raw == null || !PasswordPolicy.fitsBcrypt(raw)) {
      // Vẫn tốn một lần BCrypt để thời gian phản hồi không khác.
      encoder.matches("", dummyHash);
      return false;
    }
    return encoder.matches(raw, hash);
  }

  private Profile profile(UserRecord user) {
    List<String> permissions = rolePermissions.of(user.role()).stream().sorted().toList();
    return new Profile(user.id(), user.email(), user.displayName(), user.role(), permissions);
  }
}
