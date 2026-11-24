package vn.signa.identity.web;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import vn.signa.shared.error.ApiException;
import vn.signa.shared.error.ErrorCode;

/** Phiên của request hiện tại, chỉ dùng trong identity. */
final class CurrentSession {

  private CurrentSession() {}

  static SessionAuthentication get() {
    Authentication auth = SecurityContextHolder.getContext().getAuthentication();
    if (auth instanceof SessionAuthentication session) {
      return session;
    }
    throw new ApiException(ErrorCode.UNAUTHENTICATED);
  }
}
