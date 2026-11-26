package vn.signa.identity.web;

import static org.mockito.Mockito.when;

import java.util.Optional;
import vn.signa.identity.internal.SessionService;
import vn.signa.identity.internal.SessionService.ActiveSession;

/** Token giả cho {@code @WebMvcTest}: mỗi vai trò một token 43 ký tự, phiên do mock trả về. */
final class WebTestSessions {

  private WebTestSessions() {}

  static String token(String role) {
    String base = ("tok" + role + "x".repeat(43)).replaceAll("[^A-Za-z0-9_-]", "_");
    return base.substring(0, 43);
  }

  static String sessionId(String role) {
    return "sid-" + role;
  }

  static String userId(String role) {
    return "u-" + role.toLowerCase();
  }

  /** Cho {@code role} đăng nhập; trả header {@code Authorization}. */
  static String signIn(SessionService sessions, String role) {
    String token = token(role);
    when(sessions.authenticate(token))
        .thenReturn(Optional.of(new ActiveSession(sessionId(role), userId(role), role)));
    return "Bearer " + token;
  }
}
