package vn.signa.identity.web;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.Locale;
import vn.signa.identity.internal.Client;

/** Thân request của {@code /auth} và {@code /me} (docs/07 §3). */
final class AuthRequests {

  /** Giới hạn độ dài khi chỉ so khớp mật khẩu, để request rác không tốn nhiều bộ nhớ. */
  static final int MAX_PASSWORD_INPUT = 1024;

  private AuthRequests() {}

  record Register(
      @NotBlank @Email @Size(max = 255) String email,
      @ValidPassword String password,
      @NotBlank @Size(min = 1, max = 100) String displayName) {
    Register {
      email = normalizeEmail(email);
      displayName = displayName == null ? null : displayName.trim();
    }

    @Override
    public String toString() {
      return "Register[email=" + email + ", displayName=" + displayName + "]";
    }
  }

  record Login(
      @NotBlank @Size(max = 255) String email,
      @NotEmpty @Size(max = MAX_PASSWORD_INPUT) String password,
      @NotNull Client client) {
    Login {
      email = normalizeEmail(email);
    }

    @Override
    public String toString() {
      return "Login[email=" + email + ", client=" + client + "]";
    }
  }

  record UpdateMe(@NotBlank @Size(min = 1, max = 100) String displayName) {
    UpdateMe {
      displayName = displayName == null ? null : displayName.trim();
    }
  }

  record ChangePassword(
      @NotEmpty @Size(max = MAX_PASSWORD_INPUT) String currentPassword,
      @ValidPassword String newPassword) {
    @Override
    public String toString() {
      return "ChangePassword[***]";
    }
  }

  static String normalizeEmail(String email) {
    return email == null ? null : email.trim().toLowerCase(Locale.ROOT);
  }
}
