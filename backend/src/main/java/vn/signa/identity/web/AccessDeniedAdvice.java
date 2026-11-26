package vn.signa.identity.web;

import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.AuthenticationTrustResolver;
import org.springframework.security.authentication.AuthenticationTrustResolverImpl;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import vn.signa.shared.error.ErrorCode;
import vn.signa.shared.web.ErrorResponse;
import vn.signa.shared.web.RequestIdFilter;

/**
 * {@code @PreAuthorize} từ chối bên trong controller, nên lỗi rơi vào {@code @RestControllerAdvice}
 * trước khi tới bộ lọc của Spring Security, và handler {@code Exception} chung sẽ trả {@code 500}.
 * Advice này chạy trước handler chung: khách nhận {@code 401}, người đã đăng nhập mà thiếu quyền
 * nhận {@code 403}, cùng định dạng {@link ErrorResponse}.
 */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
class AccessDeniedAdvice {

  private static final AuthenticationTrustResolver TRUST = new AuthenticationTrustResolverImpl();

  @ExceptionHandler(AccessDeniedException.class)
  ResponseEntity<ErrorResponse> denied(AccessDeniedException e, HttpServletRequest request) {
    Authentication auth = SecurityContextHolder.getContext().getAuthentication();
    boolean guest = auth == null || !auth.isAuthenticated() || TRUST.isAnonymous(auth);
    return respond(guest ? ErrorCode.UNAUTHENTICATED : ErrorCode.FORBIDDEN, request);
  }

  @ExceptionHandler(AuthenticationException.class)
  ResponseEntity<ErrorResponse> unauthenticated(
      AuthenticationException e, HttpServletRequest request) {
    return respond(ErrorCode.UNAUTHENTICATED, request);
  }

  private static ResponseEntity<ErrorResponse> respond(ErrorCode code, HttpServletRequest request) {
    return ResponseEntity.status(code.status())
        .body(
            new ErrorResponse(
                code.name(), code.defaultMessage(), List.of(), RequestIdFilter.current(request)));
  }
}
