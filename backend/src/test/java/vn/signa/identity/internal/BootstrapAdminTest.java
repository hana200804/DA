package vn.signa.identity.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.transaction.support.TransactionOperations;
import vn.signa.audit.api.ActorType;
import vn.signa.audit.api.AuditEntityType;
import vn.signa.audit.api.AuditEntry;
import vn.signa.audit.api.AuditRecorder;
import vn.signa.identity.internal.BootstrapAdmin.Outcome;

class BootstrapAdminTest {

  private final MutableClock clock = new MutableClock(Instant.parse("2026-11-23T08:00:00Z"));
  private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder(4);
  private final UserRepository users = mock(UserRepository.class);
  private final AuditRecorder audit = mock(AuditRecorder.class);

  private BootstrapAdmin bootstrap(String email, String password) {
    return new BootstrapAdmin(
        new IdentityProperties(new IdentityProperties.BootstrapAdmin(email, password, null)),
        users,
        encoder,
        audit,
        TransactionOperations.withoutTransaction(),
        clock);
  }

  @Test
  void skipsWhenEitherVariableIsMissing() {
    assertThat(bootstrap(null, null).run()).isEqualTo(Outcome.NOT_CONFIGURED);
    assertThat(bootstrap("admin@signa.vn", "").run()).isEqualTo(Outcome.NOT_CONFIGURED);
    assertThat(bootstrap(" ", "mat-khau-123").run()).isEqualTo(Outcome.NOT_CONFIGURED);
    verifyNoInteractions(users, audit);
  }

  @Test
  void skipsWhenAnAdminExists() {
    when(users.existsWithRole("ADMIN")).thenReturn(true);

    assertThat(bootstrap("admin@signa.vn", "mat-khau-123").run()).isEqualTo(Outcome.ADMIN_EXISTS);
    verify(users, never()).insert(any(), any());
  }

  @Test
  void createsAdminAndRecordsSystemRoleChange() {
    when(users.findByEmail("admin@signa.vn")).thenReturn(Optional.empty());

    assertThat(bootstrap(" Admin@Signa.VN ", "mat-khau-123").run()).isEqualTo(Outcome.CREATED);

    ArgumentCaptor<UserRecord> saved = ArgumentCaptor.forClass(UserRecord.class);
    verify(users).insert(saved.capture(), any());
    UserRecord admin = saved.getValue();
    assertThat(admin.email()).isEqualTo("admin@signa.vn");
    assertThat(admin.role()).isEqualTo("ADMIN");
    assertThat(admin.displayName()).isEqualTo(BootstrapAdmin.DEFAULT_DISPLAY_NAME);
    assertThat(encoder.matches("mat-khau-123", admin.passwordHash())).isTrue();

    ArgumentCaptor<AuditEntry> entry = ArgumentCaptor.forClass(AuditEntry.class);
    verify(audit).record(entry.capture());
    assertThat(entry.getValue().entityType()).isEqualTo(AuditEntityType.USER);
    assertThat(entry.getValue().entityId()).isEqualTo(admin.id());
    assertThat(entry.getValue().action()).isEqualTo("ROLE_CHANGED");
    assertThat(entry.getValue().actorType()).isEqualTo(ActorType.SYSTEM);
    assertThat(entry.getValue().actorId()).isNull();
    assertThat(entry.getValue().after()).isEqualTo(java.util.Map.of("role", "ADMIN"));
  }

  @Test
  void neverPromotesAnExistingAccount() {
    when(users.findByEmail("admin@signa.vn"))
        .thenReturn(
            Optional.of(
                new UserRecord("u-1", "admin@signa.vn", "h", "A", "USER", UserStatus.ACTIVE)));

    assertThat(bootstrap("admin@signa.vn", "mat-khau-123").run()).isEqualTo(Outcome.EMAIL_TAKEN);
    verify(users, never()).insert(any(), any());
    verifyNoInteractions(audit);
  }

  @Test
  void weakPasswordStopsStartup() {
    assertThatThrownBy(() -> bootstrap("admin@signa.vn", "ngan").run())
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("SIGNA_BOOTSTRAP_ADMIN_PASSWORD")
        .hasMessageNotContaining("ngan");
  }
}
