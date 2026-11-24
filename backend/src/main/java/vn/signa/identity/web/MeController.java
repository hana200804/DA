package vn.signa.identity.web;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import vn.signa.identity.internal.AccountService;

/** Tài khoản của người đang đăng nhập (docs/07 §3). */
@RestController
@RequestMapping("/api/v1/me")
class MeController {

  private final AccountService accounts;

  MeController(AccountService accounts) {
    this.accounts = accounts;
  }

  @GetMapping
  AuthResponses.Me me() {
    return AuthResponses.Me.of(accounts.profile(CurrentSession.get().getName()));
  }

  @PatchMapping
  AuthResponses.Me update(@Valid @RequestBody AuthRequests.UpdateMe body) {
    return AuthResponses.Me.of(accounts.rename(CurrentSession.get().getName(), body.displayName()));
  }

  @PutMapping("/password")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  void changePassword(@Valid @RequestBody AuthRequests.ChangePassword body) {
    SessionAuthentication session = CurrentSession.get();
    accounts.changePassword(
        session.getName(), session.sessionId(), body.currentPassword(), body.newPassword());
  }
}
