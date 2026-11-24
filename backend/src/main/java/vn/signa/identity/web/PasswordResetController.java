package vn.signa.identity.web;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import vn.signa.identity.internal.PasswordResetService;

@RestController
@RequestMapping("/api/v1/auth/password")
class PasswordResetController {

  private final PasswordResetService passwords;

  PasswordResetController(PasswordResetService passwords) {
    this.passwords = passwords;
  }

  @PostMapping("/forgot")
  @ResponseStatus(HttpStatus.ACCEPTED)
  void forgot(@Valid @RequestBody Forgot body, HttpServletRequest request) {
    passwords.forgot(body.email(), request.getRemoteAddr());
  }

  @PostMapping("/reset")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  void reset(@Valid @RequestBody Reset body) {
    passwords.reset(body.email(), body.code(), body.newPassword());
  }

  record Forgot(@NotBlank @Email @Size(max = 255) String email) {
    Forgot {
      email = AuthRequests.normalizeEmail(email);
    }
  }

  record Reset(
      @NotBlank @Email @Size(max = 255) String email,
      @NotNull @Size(max = AuthRequests.MAX_PASSWORD_INPUT) String code,
      @ValidPassword String newPassword) {
    Reset {
      email = AuthRequests.normalizeEmail(email);
    }

    @Override
    public String toString() {
      return "Reset[***]";
    }
  }
}
