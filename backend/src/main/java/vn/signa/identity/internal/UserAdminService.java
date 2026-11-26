package vn.signa.identity.internal;

import java.time.Clock;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionOperations;
import vn.signa.audit.api.AuditEntityType;
import vn.signa.audit.api.AuditEntry;
import vn.signa.audit.api.AuditRecorder;
import vn.signa.identity.api.SessionRevoker;
import vn.signa.shared.error.ApiException;
import vn.signa.shared.error.ErrorCode;

/** Quản lý người dùng: tìm, đổi vai trò, khoá, mở khoá (FR-36). */
@Service
public class UserAdminService {

  static final String ADMIN = "ADMIN";
  static final String SELF_CHANGE = "Không thể tự hạ vai trò hay tự khoá chính mình";
  static final String LAST_ADMIN = "Phải còn ít nhất một quản trị viên đang hoạt động";

  private static final Logger log = LoggerFactory.getLogger(UserAdminService.class);

  private final UserRepository users;
  private final AuditRecorder audit;
  private final SessionRevoker sessions;
  private final TransactionOperations tx;
  private final Clock clock;

  UserAdminService(
      UserRepository users,
      AuditRecorder audit,
      SessionRevoker sessions,
      TransactionOperations tx,
      Clock clock) {
    this.users = users;
    this.audit = audit;
    this.sessions = sessions;
    this.tx = tx;
    this.clock = clock;
  }

  /** Đếm và đọc trang trong cùng một snapshot. */
  @Transactional(readOnly = true)
  public UserPage search(UserSearch search) {
    long total = users.countManaged(search);
    List<ManagedUser> items =
        total > search.offset() ? users.findManaged(search) : List.<ManagedUser>of();
    return new UserPage(items, search.page(), search.size(), total);
  }

  /**
   * Đổi {@code role} và/hoặc {@code status} ({@code null} là giữ nguyên). Không đổi gì thì không
   * ghi lịch sử. Khoá hoặc đổi vai trò tăng phiên bản xác thực và dọn phiên sau commit.
   */
  public void update(String actorId, String targetId, String role, UserStatus status) {
    tx.executeWithoutResult(s -> apply(actorId, targetId, role, status));
  }

  private void apply(String actorId, String targetId, String role, UserStatus status) {
    int activeAdmins = users.lockActiveAdmins().size();
    UserRecord target = users.findByIdForUpdate(targetId).orElseThrow(ApiException::notFound);
    String newRole = role == null ? target.role() : role;
    UserStatus newStatus = status == null ? target.status() : status;
    boolean roleChanged = !newRole.equals(target.role());
    boolean statusChanged = newStatus != target.status();
    if (!roleChanged && !statusChanged) {
      return;
    }
    checkRules(actorId, target, newRole, newStatus, activeAdmins);

    boolean revoke = roleChanged || newStatus == UserStatus.LOCKED;
    users.updateRoleAndStatus(targetId, newRole, newStatus, revoke, clock.instant());
    if (roleChanged) {
      audit.record(
          AuditEntry.byUser(
              actorId,
              AuditEntityType.USER,
              targetId,
              "ROLE_CHANGED",
              Map.of("role", target.role()),
              Map.of("role", newRole),
              null));
    }
    if (statusChanged) {
      audit.record(
          AuditEntry.byUser(
              actorId,
              AuditEntityType.USER,
              targetId,
              newStatus == UserStatus.LOCKED ? "LOCKED" : "UNLOCKED",
              Map.of("status", target.status().name()),
              Map.of("status", newStatus.name()),
              null));
    }
    if (revoke) {
      sessions.revokeOlderAfterCommit(targetId, target.credentialVersion() + 1);
    }
    log.info(
        "Quản trị người dùng actorId={} userId={} role={} status={}",
        actorId,
        targetId,
        newRole,
        newStatus);
  }

  /**
   * FR-36: không tự hạ vai trò, không tự khoá; không bỏ quản trị viên {@code ACTIVE} cuối cùng.
   *
   * @param activeAdmins số quản trị viên {@code ACTIVE} hiện có, đã khoá dòng
   */
  static void checkRules(
      String actorId, UserRecord target, String newRole, UserStatus newStatus, int activeAdmins) {
    boolean self = target.id().equals(actorId);
    if (self && (!newRole.equals(target.role()) || newStatus == UserStatus.LOCKED)) {
      throw new ApiException(ErrorCode.BUSINESS_RULE, SELF_CHANGE);
    }
    boolean wasActiveAdmin = ADMIN.equals(target.role()) && target.status() == UserStatus.ACTIVE;
    boolean staysActiveAdmin = ADMIN.equals(newRole) && newStatus == UserStatus.ACTIVE;
    if (wasActiveAdmin && !staysActiveAdmin && activeAdmins <= 1) {
      throw new ApiException(ErrorCode.BUSINESS_RULE, LAST_ADMIN);
    }
  }
}
