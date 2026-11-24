package vn.signa.identity.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import vn.signa.identity.internal.AccountService.LoginResult;
import vn.signa.identity.internal.SessionService.IssuedSession;
import vn.signa.identity.internal.UserRepository.AuthState;
import vn.signa.shared.error.ApiException;
import vn.signa.shared.error.ErrorCode;

class AccountServiceTest {

  private static final String PASSWORD = "dung-mat-khau";

  private final MutableClock clock = new MutableClock(Instant.parse("2026-11-23T08:00:00Z"));

  /** Cost 4 cho nhanh; cost thật kiểm ở {@link PasswordConfigTest}. */
  private final PasswordEncoder encoder = new BCryptPasswordEncoder(4);

  private final UserRepository users = mock(UserRepository.class);
  private final InMemorySessionStore store = new InMemorySessionStore(clock);
  private final SessionService sessions = new SessionService(store, users, clock);
  private final RolePermissions rolePermissions = new RolePermissions(null);
  private final LoginRateLimiter limiter = new LoginRateLimiter(new InMemoryAttemptCounter(clock));
  private AccountService accounts;

  @BeforeEach
  void setUp() {
    rolePermissions.replace(
        Map.of(
            "USER", Set.of("submission:create", "sign:rate", "report:create"),
            "MODERATOR", Set.of("submission:create", "review:decide")));
    PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
    when(transactionManager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());

    accounts =
        new AccountService(
            users,
            encoder,
            sessions,
            rolePermissions,
            limiter,
            new TransactionTemplate(transactionManager),
            clock);
  }

  private UserRecord user(String role, UserStatus status) {
    return new UserRecord("u-1", "a@b.vn", encoder.encode(PASSWORD), "Nguyễn Văn A", role, status);
  }

  private void givenLogin(UserRecord user) {
    when(users.findByEmail(user.email())).thenReturn(Optional.of(user));
    when(users.findByIdForUpdate(user.id())).thenReturn(Optional.of(user));
    when(users.findAuthState(user.id()))
        .thenReturn(
            Optional.of(new AuthState(user.role(), user.status(), user.credentialVersion(), null)));
  }

  @Test
  void registerStoresBcryptHashAndUserRole() {
    var profile = accounts.register("a@b.vn", PASSWORD, "Nguyễn Văn A");

    ArgumentCaptor<UserRecord> saved = ArgumentCaptor.forClass(UserRecord.class);
    verify(users).insert(saved.capture(), eq(clock.instant()));
    assertThat(saved.getValue().passwordHash()).startsWith("$2a$").isNotEqualTo(PASSWORD);
    assertThat(encoder.matches(PASSWORD, saved.getValue().passwordHash())).isTrue();
    assertThat(saved.getValue().role()).isEqualTo("USER");
    assertThat(saved.getValue().status()).isEqualTo(UserStatus.ACTIVE);
    assertThat(profile.role()).isEqualTo("USER");
    assertThat(profile.permissions())
        .containsExactly("report:create", "sign:rate", "submission:create");
  }

  @Test
  void duplicateEmailIsConflict() {
    doThrow(new DuplicateKeyException("uq_users_email")).when(users).insert(any(), any());

    assertThatThrownBy(() -> accounts.register("a@b.vn", PASSWORD, "A"))
        .isInstanceOfSatisfying(
            ApiException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.CONFLICT));
  }

  @Test
  void loginIssuesSessionAndTouchesLastLogin() {
    givenLogin(user("USER", UserStatus.ACTIVE));

    LoginResult result = accounts.login("a@b.vn", PASSWORD, Client.MOBILE, "ip");

    assertThat(SessionTokens.isWellFormed(result.token())).isTrue();
    assertThat(result.expiresAt()).isEqualTo(Instant.parse("2026-12-23T08:00:00Z"));
    assertThat(result.user().id()).isEqualTo("u-1");
    assertThat(sessions.authenticate(result.token())).isPresent();
    verify(users).touchLastLogin("u-1", clock.instant());
  }

  @Test
  void loginCannotIssueASessionIfPasswordChangedAfterVerification() {
    UserRecord beforeReset = user("USER", UserStatus.ACTIVE);
    givenLogin(beforeReset);
    when(users.findByIdForUpdate(beforeReset.id()))
        .thenReturn(
            Optional.of(
                new UserRecord(
                    beforeReset.id(),
                    beforeReset.email(),
                    "hash-moi",
                    beforeReset.displayName(),
                    beforeReset.role(),
                    beforeReset.status(),
                    1)));

    assertThat(loginFails("a@b.vn", PASSWORD, Client.MOBILE).code())
        .isEqualTo(ErrorCode.UNAUTHENTICATED);
    assertThat(store.size()).isZero();
  }

  @Test
  void unknownEmailAndWrongPasswordLookTheSame() {
    givenLogin(user("USER", UserStatus.ACTIVE));
    when(users.findByEmail("x@b.vn")).thenReturn(Optional.empty());

    ApiException wrong = loginFails("a@b.vn", "sai-mat-khau", Client.MOBILE);
    ApiException unknown = loginFails("x@b.vn", PASSWORD, Client.MOBILE);

    assertThat(wrong.code()).isEqualTo(ErrorCode.UNAUTHENTICATED);
    assertThat(unknown.code()).isEqualTo(ErrorCode.UNAUTHENTICATED);
    assertThat(wrong.getMessage()).isEqualTo(unknown.getMessage());
    assertThat(store.size()).isZero();
  }

  @Test
  void lockedAccountIsForbiddenOnlyWithCorrectPassword() {
    givenLogin(user("USER", UserStatus.LOCKED));

    assertThat(loginFails("a@b.vn", "sai-mat-khau", Client.MOBILE).code())
        .isEqualTo(ErrorCode.UNAUTHENTICATED);
    assertThat(loginFails("a@b.vn", PASSWORD, Client.MOBILE).code()).isEqualTo(ErrorCode.FORBIDDEN);
    assertThat(store.size()).isZero();
    verify(users, never()).touchLastLogin(anyString(), any());
  }

  @Test
  void webLoginNeedsReviewPermission() {
    givenLogin(user("USER", UserStatus.ACTIVE));
    assertThat(loginFails("a@b.vn", PASSWORD, Client.WEB).code()).isEqualTo(ErrorCode.FORBIDDEN);

    givenLogin(user("MODERATOR", UserStatus.ACTIVE));
    LoginResult result = accounts.login("a@b.vn", PASSWORD, Client.WEB, "ip");
    assertThat(result.expiresAt()).isEqualTo(Instant.parse("2026-11-23T16:00:00Z"));
  }

  @Test
  void eleventhAttemptIsRateLimitedEvenWithCorrectPassword() {
    givenLogin(user("USER", UserStatus.ACTIVE));
    for (int i = 0; i < 10; i++) {
      assertThat(loginFails("a@b.vn", "sai-mat-khau", Client.MOBILE).code())
          .isEqualTo(ErrorCode.UNAUTHENTICATED);
    }

    ApiException limited = loginFails("a@b.vn", PASSWORD, Client.MOBILE);
    assertThat(limited.code()).isEqualTo(ErrorCode.RATE_LIMITED);
    assertThat(limited.retryAfterSeconds()).isEqualTo(900);
  }

  @Test
  void successResetsFailureCounter() {
    givenLogin(user("USER", UserStatus.ACTIVE));
    for (int i = 0; i < 9; i++) {
      loginFails("a@b.vn", "sai-mat-khau", Client.MOBILE);
    }
    accounts.login("a@b.vn", PASSWORD, Client.MOBILE, "ip");
    for (int i = 0; i < 9; i++) {
      assertThat(loginFails("a@b.vn", "sai-mat-khau", Client.MOBILE).code())
          .isEqualTo(ErrorCode.UNAUTHENTICATED);
    }
  }

  @Test
  void passwordOver72BytesNeverMatches() {
    givenLogin(user("USER", UserStatus.ACTIVE));

    assertThat(loginFails("a@b.vn", "ệ".repeat(30), Client.MOBILE).code())
        .isEqualTo(ErrorCode.UNAUTHENTICATED);
  }

  @Test
  void changePasswordRejectsWrongCurrentPassword() {
    when(users.findById("u-1")).thenReturn(Optional.of(user("USER", UserStatus.ACTIVE)));

    assertThatThrownBy(() -> accounts.changePassword("u-1", "s", "sai-mat-khau", "moi-12345"))
        .isInstanceOfSatisfying(
            ApiException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.BUSINESS_RULE));
    verify(users, never())
        .updateActivePasswordHash(anyString(), anyInt(), anyString(), anyString(), any());
  }

  @Test
  void changePasswordRevokesOtherSessionsOnly() {
    UserRecord user = user("USER", UserStatus.ACTIVE);
    when(users.findById("u-1")).thenReturn(Optional.of(user));
    IssuedSession current = sessions.issue("u-1", 0, Client.MOBILE);
    IssuedSession other = sessions.issue("u-1", 0, Client.WEB);
    when(users.updateActivePasswordHash(
            eq("u-1"), eq(0), anyString(), eq(current.sessionId()), any()))
        .thenReturn(true);

    TransactionSynchronizationManager.initSynchronization();
    try {
      accounts.changePassword("u-1", current.sessionId(), PASSWORD, "mat-khau-moi");
      assertThat(store.size()).isEqualTo(2);
      assertThat(store.find(current.sessionId()))
          .get()
          .extracting(Session::credentialVersion)
          .isEqualTo(0);
      when(users.findAuthState("u-1"))
          .thenReturn(
              Optional.of(new AuthState("USER", UserStatus.ACTIVE, 1, current.sessionId())));
      TransactionSynchronizationManager.getSynchronizations()
          .forEach(TransactionSynchronization::afterCommit);
    } finally {
      TransactionSynchronizationManager.clearSynchronization();
    }

    ArgumentCaptor<String> hash = ArgumentCaptor.forClass(String.class);
    verify(users)
        .updateActivePasswordHash(
            eq("u-1"), eq(0), hash.capture(), eq(current.sessionId()), eq(clock.instant()));
    assertThat(encoder.matches("mat-khau-moi", hash.getValue())).isTrue();
    assertThat(sessions.authenticate(current.token())).isPresent();
    assertThat(sessions.authenticate(other.token())).isEmpty();
  }

  @Test
  void changePasswordCannotReplaceAPasswordResetAfterVerification() {
    when(users.findById("u-1")).thenReturn(Optional.of(user("USER", UserStatus.ACTIVE)));

    assertThatThrownBy(() -> accounts.changePassword("u-1", "s", PASSWORD, "mat-khau-moi"))
        .isInstanceOfSatisfying(
            ApiException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.BUSINESS_RULE));
  }

  @Test
  void repeatedWrongCurrentPasswordIsRateLimited() {
    when(users.findById("u-1")).thenReturn(Optional.of(user("USER", UserStatus.ACTIVE)));
    for (int i = 0; i < 10; i++) {
      assertThatThrownBy(() -> accounts.changePassword("u-1", "s", "sai-mat-khau", "moi-12345"))
          .isInstanceOfSatisfying(
              ApiException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.BUSINESS_RULE));
    }
    assertThatThrownBy(() -> accounts.changePassword("u-1", "s", PASSWORD, "moi-12345"))
        .isInstanceOfSatisfying(
            ApiException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.RATE_LIMITED));
  }

  @Test
  void profileOfDeletedUserIsUnauthenticated() {
    when(users.findById("u-9")).thenReturn(Optional.empty());

    assertThatThrownBy(() -> accounts.profile("u-9"))
        .isInstanceOfSatisfying(
            ApiException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.UNAUTHENTICATED));
  }

  private ApiException loginFails(String email, String password, Client client) {
    try {
      accounts.login(email, password, client, "ip");
    } catch (ApiException e) {
      return e;
    }
    throw new AssertionError("Đăng nhập lẽ ra phải lỗi");
  }
}
