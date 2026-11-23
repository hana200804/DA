package vn.signa.identity.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
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
import vn.signa.identity.internal.AccountService.LoginResult;
import vn.signa.identity.internal.SessionService.IssuedSession;
import vn.signa.shared.error.ApiException;
import vn.signa.shared.error.ErrorCode;

class AccountServiceTest {

  private static final String PASSWORD = "dung-mat-khau";

  private final MutableClock clock = new MutableClock(Instant.parse("2026-11-23T08:00:00Z"));

  /** Cost 4 cho nhanh; cost thật kiểm ở {@link PasswordConfigTest}. */
  private final PasswordEncoder encoder = new BCryptPasswordEncoder(4);

  private final UserRepository users = mock(UserRepository.class);
  private final InMemorySessionStore store = new InMemorySessionStore(clock);
  private final SessionService sessions = new SessionService(store, clock);
  private final RolePermissions rolePermissions = new RolePermissions(null);
  private final LoginRateLimiter limiter = new LoginRateLimiter(new InMemoryAttemptCounter(clock));
  private AccountService accounts;

  @BeforeEach
  void setUp() {
    rolePermissions.replace(
        Map.of(
            "USER", Set.of("submission:create", "sign:rate", "report:create"),
            "MODERATOR", Set.of("submission:create", "review:decide")));
    accounts = new AccountService(users, encoder, sessions, rolePermissions, limiter, clock);
  }

  private UserRecord user(String role, UserStatus status) {
    return new UserRecord("u-1", "a@b.vn", encoder.encode(PASSWORD), "Nguyễn Văn A", role, status);
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
    when(users.findByEmail("a@b.vn")).thenReturn(Optional.of(user("USER", UserStatus.ACTIVE)));

    LoginResult result = accounts.login("a@b.vn", PASSWORD, Client.MOBILE, "ip");

    assertThat(SessionTokens.isWellFormed(result.token())).isTrue();
    assertThat(result.expiresAt()).isEqualTo(Instant.parse("2026-12-23T08:00:00Z"));
    assertThat(result.user().id()).isEqualTo("u-1");
    assertThat(sessions.authenticate(result.token())).isPresent();
    verify(users).touchLastLogin("u-1", clock.instant());
  }

  @Test
  void unknownEmailAndWrongPasswordLookTheSame() {
    when(users.findByEmail("a@b.vn")).thenReturn(Optional.of(user("USER", UserStatus.ACTIVE)));
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
    when(users.findByEmail("a@b.vn")).thenReturn(Optional.of(user("USER", UserStatus.LOCKED)));

    assertThat(loginFails("a@b.vn", "sai-mat-khau", Client.MOBILE).code())
        .isEqualTo(ErrorCode.UNAUTHENTICATED);
    assertThat(loginFails("a@b.vn", PASSWORD, Client.MOBILE).code()).isEqualTo(ErrorCode.FORBIDDEN);
    assertThat(store.size()).isZero();
    verify(users, never()).touchLastLogin(anyString(), any());
  }

  @Test
  void webLoginNeedsReviewPermission() {
    when(users.findByEmail("a@b.vn")).thenReturn(Optional.of(user("USER", UserStatus.ACTIVE)));
    assertThat(loginFails("a@b.vn", PASSWORD, Client.WEB).code()).isEqualTo(ErrorCode.FORBIDDEN);

    when(users.findByEmail("a@b.vn")).thenReturn(Optional.of(user("MODERATOR", UserStatus.ACTIVE)));
    LoginResult result = accounts.login("a@b.vn", PASSWORD, Client.WEB, "ip");
    assertThat(result.expiresAt()).isEqualTo(Instant.parse("2026-11-23T16:00:00Z"));
  }

  @Test
  void eleventhAttemptIsRateLimitedEvenWithCorrectPassword() {
    when(users.findByEmail("a@b.vn")).thenReturn(Optional.of(user("USER", UserStatus.ACTIVE)));
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
    when(users.findByEmail("a@b.vn")).thenReturn(Optional.of(user("USER", UserStatus.ACTIVE)));
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
    when(users.findByEmail("a@b.vn")).thenReturn(Optional.of(user("USER", UserStatus.ACTIVE)));

    assertThat(loginFails("a@b.vn", "ệ".repeat(30), Client.MOBILE).code())
        .isEqualTo(ErrorCode.UNAUTHENTICATED);
  }

  @Test
  void changePasswordRejectsWrongCurrentPassword() {
    when(users.findById("u-1")).thenReturn(Optional.of(user("USER", UserStatus.ACTIVE)));

    assertThatThrownBy(() -> accounts.changePassword("u-1", "s", "sai-mat-khau", "moi-12345"))
        .isInstanceOfSatisfying(
            ApiException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.BUSINESS_RULE));
    verify(users, never()).updatePasswordHash(anyString(), anyString(), any());
  }

  @Test
  void changePasswordRevokesOtherSessionsOnly() {
    when(users.findById("u-1")).thenReturn(Optional.of(user("USER", UserStatus.ACTIVE)));
    IssuedSession current = sessions.issue("u-1", "USER", Client.MOBILE);
    IssuedSession other = sessions.issue("u-1", "USER", Client.WEB);

    accounts.changePassword("u-1", current.sessionId(), PASSWORD, "mat-khau-moi");

    ArgumentCaptor<String> hash = ArgumentCaptor.forClass(String.class);
    verify(users).updatePasswordHash(eq("u-1"), hash.capture(), eq(clock.instant()));
    assertThat(encoder.matches("mat-khau-moi", hash.getValue())).isTrue();
    assertThat(sessions.authenticate(current.token())).isPresent();
    assertThat(sessions.authenticate(other.token())).isEmpty();
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
