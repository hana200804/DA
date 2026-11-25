package vn.signa.sign.web.admin;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;

import java.util.Arrays;
import java.util.List;
import java.util.Set;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import vn.signa.identity.api.AuthenticatedUser;

/** Người đăng nhập giả cho test controller quản trị biển. */
final class AdminRequests {

  static final String ACTOR = "moderator-1";

  private AdminRequests() {}

  static RequestPostProcessor as(String... permissions) {
    var user = new AuthenticatedUser(ACTOR, "MODERATOR", Set.of(permissions));
    List<SimpleGrantedAuthority> authorities =
        Arrays.stream(permissions).map(SimpleGrantedAuthority::new).toList();
    return authentication(
        UsernamePasswordAuthenticationToken.authenticated(user, null, authorities));
  }

  static RequestPostProcessor moderator() {
    return as("review:decide", "sign:edit");
  }
}
