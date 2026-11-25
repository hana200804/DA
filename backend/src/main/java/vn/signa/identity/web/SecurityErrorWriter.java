package vn.signa.identity.web;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;
import vn.signa.shared.error.ErrorCode;
import vn.signa.shared.web.ErrorResponse;
import vn.signa.shared.web.RequestIdFilter;

/**
 * Lỗi do Spring Security sinh ra (thiếu phiên, thiếu quyền ở mức URL) đi qua cùng định dạng {@link
 * ErrorResponse} với lỗi của controller (docs/07 §2).
 */
@Component
class SecurityErrorWriter implements AuthenticationEntryPoint, AccessDeniedHandler {

  private final JsonMapper json;

  SecurityErrorWriter(JsonMapper json) {
    this.json = json;
  }

  @Override
  public void commence(
      HttpServletRequest request, HttpServletResponse response, AuthenticationException e)
      throws IOException {
    response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
    write(request, response, ErrorCode.UNAUTHENTICATED);
  }

  @Override
  public void handle(
      HttpServletRequest request, HttpServletResponse response, AccessDeniedException e)
      throws IOException {
    write(request, response, ErrorCode.FORBIDDEN);
  }

  void write(HttpServletRequest request, HttpServletResponse response, ErrorCode code)
      throws IOException {
    response.setStatus(code.status().value());
    response.setContentType(MediaType.APPLICATION_JSON_VALUE);
    response.setCharacterEncoding("UTF-8");
    json.writeValue(
        response.getOutputStream(),
        new ErrorResponse(
            code.name(), code.defaultMessage(), List.of(), RequestIdFilter.current(request)));
  }
}
