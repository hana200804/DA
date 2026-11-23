package vn.signa.identity.web;

import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import vn.signa.identity.api.AuthenticatedUser;

/**
 * Xác thực bằng phiên Redis. Authority là mã quyền, nên {@code hasAuthority('review:decide')} kiểm
 * tra đúng quyền. Mã phiên chỉ dùng trong identity (đăng xuất, đổi mật khẩu).
 */
final class SessionAuthentication extends AbstractAuthenticationToken {

  private final AuthenticatedUser user;
  private final String sessionId;

  SessionAuthentication(AuthenticatedUser user, String sessionId) {
    super(user.permissions().stream().sorted().map(SimpleGrantedAuthority::new).toList());
    this.user = user;
    this.sessionId = sessionId;
    setAuthenticated(true);
  }

  @Override
  public AuthenticatedUser getPrincipal() {
    return user;
  }

  /** Không giữ token; trả {@code null} để không có gì lộ qua {@code toString}. */
  @Override
  public Object getCredentials() {
    return null;
  }

  @Override
  public String getName() {
    return user.userId();
  }

  String sessionId() {
    return sessionId;
  }
}
