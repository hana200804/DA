package vn.signa.identity.web;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import vn.signa.identity.internal.AccountService;

/** Đăng ký, đăng nhập, đăng xuất (docs/07 §3, FR-01, FR-30). */
@RestController
@RequestMapping("/api/v1/auth")
class AuthController {

  private final AccountService accounts;

  AuthController(AccountService accounts) {
    this.accounts = accounts;
  }

  @PostMapping("/register")
  @ResponseStatus(HttpStatus.CREATED)
  AuthResponses.Me register(@Valid @RequestBody AuthRequests.Register body) {
    return AuthResponses.Me.of(
        accounts.register(body.email(), body.password(), body.displayName()));
  }

  /** IP đã qua {@code ForwardedHeaderFilter} ({@code server.forward-headers-strategy}). */
  @PostMapping("/login")
  AuthResponses.Login login(
      @Valid @RequestBody AuthRequests.Login body, HttpServletRequest request) {
    return AuthResponses.Login.of(
        accounts.login(body.email(), body.password(), body.client(), request.getRemoteAddr()));
  }

  @PostMapping("/logout")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  void logout() {
    accounts.logout(CurrentSession.get().sessionId());
  }
}
