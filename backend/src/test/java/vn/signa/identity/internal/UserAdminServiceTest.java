package vn.signa.identity.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.support.TransactionOperations;
import vn.signa.audit.api.ActorType;
import vn.signa.audit.api.AuditEntityType;
import vn.signa.audit.api.AuditEntry;
import vn.signa.audit.api.AuditRecorder;
import vn.signa.identity.api.SessionRevoker;
import vn.signa.shared.error.ApiException;
import vn.signa.shared.error.ErrorCode;

class UserAdminServiceTest {

  private static final String ACTOR = "u-admin";
  private static final String TARGET = "u-target";

  private final MutableClock clock = new MutableClock(Instant.parse("2026-11-24T08:00:00Z"));
  private final UserRepository users = mock(UserRepository.class);
  private final AuditRecorder audit = mock(AuditRecorder.class);
  private final SessionRevoker sessions = mock(SessionRevoker.class);
  private final UserAdminService service =
      new UserAdminService(
          users, audit, sessions, TransactionOperations.withoutTransaction(), clock);

  private static UserRecord user(String id, String role, UserStatus status) {
    return new UserRecord(id, id + "@signa.vn", "$2a$hash", "Tên " + id, role, status);
  }

  private void given(UserRecord target, String... activeAdmins) {
    when(users.lockActiveAdmins()).thenReturn(List.of(activeAdmins));
    when(users.findByIdForUpdate(target.id())).thenReturn(Optional.of(target));
  }

  private List<AuditEntry> recorded() {
    ArgumentCaptor<AuditEntry> entries = ArgumentCaptor.forClass(AuditEntry.class);
    verify(audit, org.mockito.Mockito.atLeast(0)).record(entries.capture());
    return entries.getAllValues();
  }

  private static ErrorCode codeOf(Runnable action) {
    try {
      action.run();
    } catch (ApiException e) {
      return e.code();
    }
    return null;
  }

  @Test
  void lockingRecordsHistoryAndSchedulesSessionCleanup() {
    given(user(TARGET, "USER", UserStatus.ACTIVE), ACTOR);

    service.update(ACTOR, TARGET, null, UserStatus.LOCKED);

    var order = inOrder(users, audit, sessions);
    order.verify(users).lockActiveAdmins();
    order.verify(users).findByIdForUpdate(TARGET);
    order
        .verify(users)
        .updateRoleAndStatus(TARGET, "USER", UserStatus.LOCKED, true, clock.instant());
    order.verify(audit).record(any());
    order.verify(sessions).revokeOlderAfterCommit(TARGET, 1);
    AuditEntry entry = recorded().getFirst();
    assertThat(entry.entityType()).isEqualTo(AuditEntityType.USER);
    assertThat(entry.entityId()).isEqualTo(TARGET);
    assertThat(entry.action()).isEqualTo("LOCKED");
    assertThat(entry.actorType()).isEqualTo(ActorType.USER);
    assertThat(entry.actorId()).isEqualTo(ACTOR);
    assertThat(entry.before()).isEqualTo(Map.of("status", "ACTIVE"));
    assertThat(entry.after()).isEqualTo(Map.of("status", "LOCKED"));
  }

  @Test
  void unlockingRecordsHistoryWithoutRevoking() {
    given(user(TARGET, "USER", UserStatus.LOCKED), ACTOR);

    service.update(ACTOR, TARGET, null, UserStatus.ACTIVE);

    assertThat(recorded())
        .singleElement()
        .satisfies(e -> {
          assertThat(e.action()).isEqualTo("UNLOCKED");
          assertThat(e.before()).isEqualTo(Map.of("status", "LOCKED"));
          assertThat(e.after()).isEqualTo(Map.of("status", "ACTIVE"));
        });
    verify(users).updateRoleAndStatus(TARGET, "USER", UserStatus.ACTIVE, false, clock.instant());
    verifyNoInteractions(sessions);
  }

  @Test
  void roleChangeRecordsBeforeAfterAndRevokes() {
    given(user(TARGET, "USER", UserStatus.ACTIVE), ACTOR);

    service.update(ACTOR, TARGET, "MODERATOR", null);

    verify(users)
        .updateRoleAndStatus(TARGET, "MODERATOR", UserStatus.ACTIVE, true, clock.instant());
    assertThat(recorded())
        .singleElement()
        .satisfies(e -> {
          assertThat(e.action()).isEqualTo("ROLE_CHANGED");
          assertThat(e.before()).isEqualTo(Map.of("role", "USER"));
          assertThat(e.after()).isEqualTo(Map.of("role", "MODERATOR"));
        });
    verify(sessions).revokeOlderAfterCommit(TARGET, 1);
  }

  @Test
  void roleAndStatusTogetherWriteTwoRows() {
    given(user(TARGET, "MODERATOR", UserStatus.ACTIVE), ACTOR);

    service.update(ACTOR, TARGET, "USER", UserStatus.LOCKED);

    assertThat(recorded()).extracting(AuditEntry::action).containsExactly("ROLE_CHANGED", "LOCKED");
  }

  @Test
  void noChangeWritesNothing() {
    given(user(TARGET, "USER", UserStatus.ACTIVE), ACTOR);

    service.update(ACTOR, TARGET, "USER", UserStatus.ACTIVE);

    verify(users, never())
        .updateRoleAndStatus(anyString(), anyString(), any(), anyBoolean(), any());
    verifyNoInteractions(audit, sessions);
  }

  @Test
  void unknownUserIs404() {
    when(users.lockActiveAdmins()).thenReturn(List.of(ACTOR));
    when(users.findByIdForUpdate(TARGET)).thenReturn(Optional.empty());

    assertThat(codeOf(() -> service.update(ACTOR, TARGET, null, UserStatus.LOCKED)))
        .isEqualTo(ErrorCode.NOT_FOUND);
    verifyNoInteractions(audit, sessions);
  }

  @Test
  void adminCannotLockOrDemoteThemself() {
    given(user(ACTOR, "ADMIN", UserStatus.ACTIVE), ACTOR, "u-other-admin");

    assertThatThrownBy(() -> service.update(ACTOR, ACTOR, null, UserStatus.LOCKED))
        .isInstanceOfSatisfying(
            ApiException.class,
            e -> {
              assertThat(e.code()).isEqualTo(ErrorCode.BUSINESS_RULE);
              assertThat(e.getMessage()).isEqualTo(UserAdminService.SELF_CHANGE);
            });
    assertThat(codeOf(() -> service.update(ACTOR, ACTOR, "MODERATOR", null)))
        .isEqualTo(ErrorCode.BUSINESS_RULE);
    verify(users, never())
        .updateRoleAndStatus(anyString(), anyString(), any(), anyBoolean(), any());
    verifyNoInteractions(audit, sessions);
  }

  @Test
  void keepingOwnRoleIsANoOp() {
    given(user(ACTOR, "ADMIN", UserStatus.ACTIVE), ACTOR);

    assertThatCode(() -> service.update(ACTOR, ACTOR, "ADMIN", UserStatus.ACTIVE))
        .doesNotThrowAnyException();
    verifyNoInteractions(audit, sessions);
  }

  @Test
  void lastActiveAdminCannotBeLockedOrDemoted() {
    given(user(TARGET, "ADMIN", UserStatus.ACTIVE), TARGET);

    assertThatThrownBy(() -> service.update(ACTOR, TARGET, null, UserStatus.LOCKED))
        .isInstanceOfSatisfying(
            ApiException.class,
            e -> {
              assertThat(e.code()).isEqualTo(ErrorCode.BUSINESS_RULE);
              assertThat(e.getMessage()).isEqualTo(UserAdminService.LAST_ADMIN);
            });
    assertThat(codeOf(() -> service.update(ACTOR, TARGET, "USER", null)))
        .isEqualTo(ErrorCode.BUSINESS_RULE);
    verifyNoInteractions(audit, sessions);
  }

  @Test
  void anotherActiveAdminAllowsLockingAnAdmin() {
    given(user(TARGET, "ADMIN", UserStatus.ACTIVE), ACTOR, TARGET);

    service.update(ACTOR, TARGET, null, UserStatus.LOCKED);

    assertThat(recorded()).extracting(AuditEntry::action).containsExactly("LOCKED");
  }

  @Test
  void lastAdminRuleIgnoresLockedOrNonAdminTargets() {
    UserRecord lockedAdmin = user(TARGET, "ADMIN", UserStatus.LOCKED);
    assertThatCode(
            () -> UserAdminService.checkRules(ACTOR, lockedAdmin, "USER", UserStatus.LOCKED, 1))
        .doesNotThrowAnyException();
    UserRecord moderator = user(TARGET, "MODERATOR", UserStatus.ACTIVE);
    assertThatCode(
            () -> UserAdminService.checkRules(ACTOR, moderator, "USER", UserStatus.LOCKED, 1))
        .doesNotThrowAnyException();
    UserRecord admin = user(TARGET, "ADMIN", UserStatus.ACTIVE);
    assertThatCode(() -> UserAdminService.checkRules(ACTOR, admin, "ADMIN", UserStatus.ACTIVE, 1))
        .doesNotThrowAnyException();
  }

  @Test
  void searchSkipsPageQueryWhenPastTheEnd() {
    UserSearch search = new UserSearch("a", null, null, 2, 10);
    when(users.countManaged(search)).thenReturn(15L);

    UserPage page = service.search(search);

    assertThat(page.items()).isEmpty();
    assertThat(page.total()).isEqualTo(15);
    assertThat(page.page()).isEqualTo(2);
    verify(users, never()).findManaged(any());
  }
}
