package vn.signa.identity.internal;

import java.time.Clock;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.core.task.TaskExecutor;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import vn.signa.identity.api.SessionRevoker;
import vn.signa.identity.internal.RedisOtpStore.Challenge;
import vn.signa.shared.error.ApiException;
import vn.signa.shared.error.ErrorCode;

/** Quản lý yêu cầu đặt lại mật khẩu bằng OTP qua email (FR-13). */
@Service
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class PasswordResetService {

  static final String INVALID_CODE = "Mã đặt lại mật khẩu không hợp lệ hoặc đã hết hạn";
  private static final int MAX_FAILURES = 5;
  private static final Logger log = LoggerFactory.getLogger(PasswordResetService.class);

  private final UserRepository users;
  private final RedisOtpStore otp;
  private final OtpCodes codes;
  private final PasswordEncoder encoder;
  private final SessionRevoker sessions;
  private final JavaMailSender mail;
  private final TaskExecutor worker;
  private final TransactionTemplate transactions;
  private final OtpProperties properties;
  private final Clock clock;

  PasswordResetService(
      UserRepository users,
      RedisOtpStore otp,
      OtpCodes codes,
      PasswordEncoder encoder,
      SessionRevoker sessions,
      @Qualifier("otpMailSender") JavaMailSender mail,
      @Qualifier("passwordOtpExecutor") TaskExecutor worker,
      TransactionTemplate transactions,
      OtpProperties properties,
      Clock clock) {
    this.users = users;
    this.otp = otp;
    this.codes = codes;
    this.encoder = encoder;
    this.sessions = sessions;
    this.mail = mail;
    this.worker = worker;
    this.transactions = transactions;
    this.properties = properties;
    this.clock = clock;
  }

  public void forgot(String email, String ip) {
    String emailHash = OtpCodes.emailHash(email);
    long retryAfter = otp.reserveSend(emailHash, ip);
    if (retryAfter > 0) {
      throw ApiException.rateLimited((int) retryAfter);
    }
    try {
      // Cả email không tồn tại cũng đi qua cùng đường gửi việc vào hàng đợi.
      worker.execute(() -> issueAndSend(email, emailHash));
    } catch (TaskRejectedException e) {
      log.warn("Hàng đợi gửi OTP đặt lại mật khẩu đã đầy");
    }
  }

  public void reset(String email, String code, String newPassword) {
    String emailHash = OtpCodes.emailHash(email);
    Challenge challenge = otp.find(emailHash);
    if (challenge == null) {
      throw invalidCode();
    }
    if (!codes.matches(code, challenge.codeHash())) {
      applyAttempt(emailHash, challenge, false);
      throw invalidCode();
    }
    UserRecord user = activeUser(email).orElseThrow(PasswordResetService::invalidCode);
    String passwordHash = encoder.encode(newPassword);
    transactions.executeWithoutResult(status -> {
      if (!users.updateActivePasswordHash(user.id(), passwordHash, clock.instant())) {
        throw invalidCode();
      }
      // Nếu một request khác đã dùng, thay hoặc làm mã hết lượt, rollback mật khẩu.
      if (!applyAttempt(emailHash, challenge, true)) {
        throw invalidCode();
      }
      sessions.revokeAll(user.id());
    });
  }

  private void issueAndSend(String email, String emailHash) {
    try {
      UserRecord user = activeUser(email).orElse(null);
      if (user == null) {
        return;
      }
      String code;
      do {
        code = codes.generate();
      } while (!otp.issue(emailHash, codes.hash(code)));

      SimpleMailMessage message = new SimpleMailMessage();
      message.setFrom(properties.smtpFrom());
      message.setTo(user.email());
      message.setSubject("Mã đặt lại mật khẩu Signa");
      message.setText("Mã đặt lại mật khẩu của bạn là " + code + ".\nMã có hiệu lực 10 phút.");
      mail.send(message);
    } catch (RuntimeException e) {
      // Ngoại lệ SMTP có thể chứa thông tin thư. Chỉ ghi thông báo chung.
      log.warn("Không thể xử lý yêu cầu gửi OTP đặt lại mật khẩu");
    }
  }

  private Optional<UserRecord> activeUser(String email) {
    return users
        .findByEmail(email)
        .filter(user -> user.status() == UserStatus.ACTIVE && user.email().equals(email));
  }

  private boolean applyAttempt(String emailHash, Challenge current, boolean matches) {
    String codeHash = current.codeHash();
    do {
      boolean delete = matches || current.attempts() + 1 >= MAX_FAILURES;
      if (otp.applyAttempt(emailHash, current, delete)) {
        return true;
      }
      // Thử lại khi một lần nhập đồng thời đổi bộ đếm, dừng nếu mã đã được thay hoặc xoá.
      current = otp.find(emailHash);
    } while (current != null && codeHash.equals(current.codeHash()));
    return false;
  }

  private static ApiException invalidCode() {
    return new ApiException(ErrorCode.BUSINESS_RULE, INVALID_CODE);
  }
}
