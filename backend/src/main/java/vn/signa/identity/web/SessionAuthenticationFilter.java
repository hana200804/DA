package vn.signa.identity.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpHeaders;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.context.SecurityContextHolderStrategy;
import org.springframework.web.filter.OncePerRequestFilter;
import vn.signa.identity.api.AuthenticatedUser;
import vn.signa.identity.internal.RolePermissions;
import vn.signa.identity.internal.SessionService;
import vn.signa.identity.internal.SessionService.ActiveSession;
import vn.signa.shared.error.ErrorCode;

/**
 * Đọc {@code Authorization: Bearer}, tra phiên Redis và trạng thái MySQL rồi đặt principal có danh
 * sách quyền (docs/03 §6.6). Token thiếu, sai hoặc đã thu hồi thì request đi tiếp như khách; đường
 * dẫn cần đăng nhập sẽ nhận {@code 401} từ entry point. Không phải bean, để Spring Boot không đăng
 * ký nó thêm một lần ngoài chuỗi Security.
 */
final class SessionAuthenticationFilter extends OncePerRequestFilter {

  static final String MDC_USER_ID = "userId";
  private static final String BEARER = "Bearer ";
  private static final Logger log = LoggerFactory.getLogger(SessionAuthenticationFilter.class);

  private final SessionService sessions;
  private final RolePermissions rolePermissions;
  private final SecurityErrorWriter errors;
  private final SecurityContextHolderStrategy contexts =
      SecurityContextHolder.getContextHolderStrategy();

  SessionAuthenticationFilter(
      SessionService sessions, RolePermissions rolePermissions, SecurityErrorWriter errors) {
    this.sessions = sessions;
    this.rolePermissions = rolePermissions;
    this.errors = errors;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    String token = bearer(request.getHeader(HttpHeaders.AUTHORIZATION));
    if (token != null) {
      Optional<ActiveSession> active;
      try {
        active = sessions.authenticate(token);
      } catch (RuntimeException e) {
        log.error("Không tra được phiên", e);
        errors.write(request, response, ErrorCode.INTERNAL_ERROR);
        return;
      }
      active.ifPresent(this::authenticate);
    }
    try {
      chain.doFilter(request, response);
    } finally {
      MDC.remove(MDC_USER_ID);
    }
  }

  private void authenticate(ActiveSession active) {
    AuthenticatedUser user =
        new AuthenticatedUser(active.userId(), active.role(), rolePermissions.of(active.role()));
    SecurityContext context = contexts.createEmptyContext();
    context.setAuthentication(new SessionAuthentication(user, active.sessionId()));
    contexts.setContext(context);
    MDC.put(MDC_USER_ID, user.userId());
  }

  static String bearer(String header) {
    if (header == null || !header.regionMatches(true, 0, BEARER, 0, BEARER.length())) {
      return null;
    }
    String token = header.substring(BEARER.length()).trim();
    return token.isEmpty() ? null : token;
  }
}
