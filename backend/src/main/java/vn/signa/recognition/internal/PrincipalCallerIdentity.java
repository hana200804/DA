package vn.signa.recognition.internal;

import java.util.Optional;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import vn.signa.identity.api.AuthenticatedUser;

/**
 * Đọc principal {@link AuthenticatedUser} mà filter phiên của lát 1 đặt vào {@code
 * SecurityContext}. Khách (không có token, hoặc xác thực ẩn danh) thì rỗng.
 */
@Component
public class PrincipalCallerIdentity implements CallerIdentity {

  @Override
  public Optional<String> userId() {
    Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
    if (authentication != null
        && authentication.isAuthenticated()
        && authentication.getPrincipal() instanceof AuthenticatedUser user) {
      return Optional.of(user.userId());
    }
    return Optional.empty();
  }
}
