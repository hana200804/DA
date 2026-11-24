package vn.signa.identity.web;

import java.time.Instant;
import java.util.List;
import vn.signa.identity.internal.AccountService.LoginResult;
import vn.signa.identity.internal.AccountService.Profile;

/** Thân response của {@code /auth} và {@code /me} (docs/07 §3). */
final class AuthResponses {

  private AuthResponses() {}

  /** {@code GET /me}: trả thẳng, không bọc trong {@code user}. */
  record Me(String id, String email, String displayName, String role, List<String> permissions) {
    static Me of(Profile p) {
      return new Me(p.id(), p.email(), p.displayName(), p.role(), p.permissions());
    }
  }

  record LoginUser(String id, String displayName, String role, List<String> permissions) {
    static LoginUser of(Profile p) {
      return new LoginUser(p.id(), p.displayName(), p.role(), p.permissions());
    }
  }

  record Login(String token, Instant expiresAt, LoginUser user) {
    static Login of(LoginResult r) {
      return new Login(r.token(), r.expiresAt(), LoginUser.of(r.user()));
    }

    @Override
    public String toString() {
      return "Login[user=" + user.id() + ", expiresAt=" + expiresAt + "]";
    }
  }
}
