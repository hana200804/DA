package vn.signa.identity.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.mail.MailSendException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;
import vn.signa.identity.api.SessionRevoker;
import vn.signa.identity.internal.RedisOtpStore.Challenge;
import vn.signa.shared.error.ApiException;
import vn.signa.shared.error.ErrorCode;

@ExtendWith(OutputCaptureExtension.class)
class PasswordResetServiceTest {

  private static final String EMAIL = "a@b.vn";
  private static final String EMAIL_HASH = OtpCodes.emailHash(EMAIL);
  private static final String IP = "203.0.113.10";
  private static final String CODE = "000042";
  private static final String NEW_PASSWORD = "pass-moi-12";
  private static final Instant NOW = Instant.parse("2026-11-24T03:00:00Z");
  private static final UserRecord ACTIVE =
      new UserRecord("u1", EMAIL, "old-hash", "Người thử", "USER", UserStatus.ACTIVE);

  private final UserRepository users = mock(UserRepository.class);
  private final RedisOtpStore otp = mock(RedisOtpStore.class);
  private final SecureRandom random = mock(SecureRandom.class);
  private final OtpCodes codes = new OtpCodes("test-key", random);
  private final PasswordEncoder encoder = mock(PasswordEncoder.class);
  private final SessionRevoker sessions = mock(SessionRevoker.class);
  private final JavaMailSender mail = mock(JavaMailSender.class);
  private final Deque<Runnable> queue = new ArrayDeque<>();
  private final TransactionTemplate transactions = mock(TransactionTemplate.class);
  private final OtpProperties properties = new OtpProperties(null, "mailpit", 1025, "otp@signa.vn");
  private final PasswordResetService service =
      new PasswordResetService(
          users,
          otp,
          codes,
          encoder,
          sessions,
          mail,
          queue::add,
          transactions,
          properties,
          Clock.fixed(NOW, ZoneOffset.UTC));

  @Test
  void unknownEmailQueuesTheSameWorkAndNeverSendsMail() {
    service.forgot(EMAIL, IP);

    assertThat(queue).hasSize(1);
    verifyNoInteractions(users, random, mail);
    queue.remove().run();

    verify(otp).reserveSend(EMAIL_HASH, IP);
    verify(users).findByEmail(EMAIL);
    verifyNoInteractions(random, mail);
  }

  @Test
  void lockedAccountNeverReceivesMail() {
    when(users.findByEmail(EMAIL))
        .thenReturn(
            Optional.of(
                new UserRecord(
                    ACTIVE.id(), EMAIL, "hash", "Người thử", "USER", UserStatus.LOCKED)));

    service.forgot(EMAIL, IP);
    queue.remove().run();

    verifyNoInteractions(random, mail);
  }

  @Test
  void sendsNewCodeWhenRandomRepeatsTheCurrentCode() {
    when(users.findByEmail(EMAIL)).thenReturn(Optional.of(ACTIVE));
    when(random.nextInt(1_000_000)).thenReturn(10, 42);
    when(otp.issue(EMAIL_HASH, codes.hash(CODE))).thenReturn(true);

    service.forgot(EMAIL, IP);
    verifyNoInteractions(users, mail);
    queue.remove().run();

    var message = ArgumentCaptor.forClass(SimpleMailMessage.class);
    var ordered = inOrder(otp, mail);
    ordered.verify(otp).issue(EMAIL_HASH, codes.hash("000010"));
    ordered.verify(otp).issue(EMAIL_HASH, codes.hash(CODE));
    ordered.verify(mail).send(message.capture());
    assertThat(message.getValue().getTo()).containsExactly(EMAIL);
    assertThat(message.getValue().getFrom()).isEqualTo("otp@signa.vn");
    assertThat(message.getValue().getSubject()).isEqualTo("Mã đặt lại mật khẩu Signa");
    assertThat(message.getValue().getText()).contains(CODE, "hiệu lực 10 phút");
  }

  @Test
  void rateLimitRejectsBeforeQueueingAndKeepsRetryAfter() {
    when(otp.reserveSend(EMAIL_HASH, IP)).thenReturn(730L);

    assertThatThrownBy(() -> service.forgot(EMAIL, IP))
        .isInstanceOfSatisfying(
            ApiException.class,
            error -> {
              assertThat(error.code()).isEqualTo(ErrorCode.RATE_LIMITED);
              assertThat(error.retryAfterSeconds()).isEqualTo(730);
            });
    assertThat(queue).isEmpty();
    verifyNoInteractions(users, mail);
  }

  @Test
  void smtpFailureDoesNotLogTheCode(CapturedOutput output) {
    when(users.findByEmail(EMAIL)).thenReturn(Optional.of(ACTIVE));
    when(random.nextInt(1_000_000)).thenReturn(42);
    when(otp.issue(EMAIL_HASH, codes.hash(CODE))).thenReturn(true);
    doThrow(new MailSendException("body=" + CODE)).when(mail).send(any(SimpleMailMessage.class));

    service.forgot(EMAIL, IP);
    queue.remove().run();

    assertThat(output.getAll())
        .contains("Không thể xử lý yêu cầu gửi OTP đặt lại mật khẩu")
        .doesNotContain("body=" + CODE);
  }

  @Test
  void wrongCodesIncrementToFourThenTheFifthDeletesTheChallenge() {
    var challenge = useChallenge();
    for (int failures = 1; failures < 5; failures++) {
      assertInvalidCode("123456");
      assertThat(challenge.get().attempts()).isEqualTo(failures);
    }

    assertInvalidCode("123456");
    assertThat(challenge.get()).isNull();
    assertInvalidCode(CODE);
    verifyNoInteractions(users, encoder, sessions);
  }

  @Test
  void changedChallengeIsNeverConsumedUsingTheOldCode() {
    Challenge original = new Challenge(codes.hash(CODE), 0);
    Challenge replacement = new Challenge(codes.hash("000043"), 0);
    when(otp.find(EMAIL_HASH)).thenReturn(original, replacement);
    activePasswordUpdate();

    assertInvalidCode(CODE);

    verify(otp).applyAttempt(EMAIL_HASH, original, true);
    verifyNoInteractions(sessions);
  }

  @Test
  void consumesCodeOnceChangesPasswordAndRevokesEverySession() {
    var challenge = useChallenge();
    activePasswordUpdate();

    service.reset(EMAIL, CODE, NEW_PASSWORD);

    assertThat(challenge.get()).isNull();
    var ordered = inOrder(users, otp, sessions);
    ordered.verify(users).updateActivePasswordHash(ACTIVE.id(), "new-hash", NOW);
    ordered.verify(otp).applyAttempt(EMAIL_HASH, new Challenge(codes.hash(CODE), 0), true);
    ordered.verify(sessions).revokeAll(ACTIVE.id());
    assertInvalidCode(CODE);
  }

  private void activePasswordUpdate() {
    when(users.findByEmail(EMAIL)).thenReturn(Optional.of(ACTIVE));
    when(encoder.encode(NEW_PASSWORD)).thenReturn("new-hash");
    when(users.updateActivePasswordHash(ACTIVE.id(), "new-hash", NOW)).thenReturn(true);
    doAnswer(
            invocation -> {
              invocation
                  .<Consumer<TransactionStatus>>getArgument(0)
                  .accept(mock(TransactionStatus.class));
              return null;
            })
        .when(transactions)
        .executeWithoutResult(any());
  }

  private AtomicReference<Challenge> useChallenge() {
    var current = new AtomicReference<>(new Challenge(codes.hash(CODE), 0));
    when(otp.find(EMAIL_HASH)).thenAnswer(invocation -> current.get());
    when(otp.applyAttempt(eq(EMAIL_HASH), any(), anyBoolean()))
        .thenAnswer(invocation -> {
          Challenge expected = invocation.getArgument(1);
          Challenge next =
              invocation.<Boolean>getArgument(2)
                  ? null
                  : new Challenge(expected.codeHash(), expected.attempts() + 1);
          return current.compareAndSet(expected, next);
        });
    return current;
  }

  private void assertInvalidCode(String code) {
    assertThatThrownBy(() -> service.reset(EMAIL, code, NEW_PASSWORD))
        .isInstanceOfSatisfying(
            ApiException.class,
            error -> {
              assertThat(error.code()).isEqualTo(ErrorCode.BUSINESS_RULE);
              assertThat(error).hasMessage(PasswordResetService.INVALID_CODE);
            });
  }
}
