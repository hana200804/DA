package vn.signa.shared.web;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.AuthenticationTrustResolver;
import org.springframework.security.authentication.AuthenticationTrustResolverImpl;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import vn.signa.shared.error.ApiException;
import vn.signa.shared.error.ErrorCode;
import vn.signa.shared.error.FieldIssue;

/** Chuyển mọi lỗi sang định dạng chung của docs/07 §2. */
@RestControllerAdvice
public class GlobalExceptionHandler {

  private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);
  private static final AuthenticationTrustResolver TRUST = new AuthenticationTrustResolverImpl();

  @ExceptionHandler(ApiException.class)
  ResponseEntity<ErrorResponse> api(ApiException e, HttpServletRequest request) {
    ResponseEntity.BodyBuilder builder = ResponseEntity.status(e.code().status());
    if (e.retryAfterSeconds() != null) {
      builder.header(HttpHeaders.RETRY_AFTER, String.valueOf(e.retryAfterSeconds()));
    }
    return builder.body(body(e.code(), e.getMessage(), e.details(), request));
  }

  /**
   * {@code @PreAuthorize} từ chối bên trong controller nên lỗi tới đây trước bộ lọc của Spring
   * Security. Khách nhận {@code 401}, người đã đăng nhập mà thiếu quyền nhận {@code 403}.
   */
  @ExceptionHandler(AccessDeniedException.class)
  ResponseEntity<ErrorResponse> denied(AccessDeniedException e, HttpServletRequest request) {
    Authentication auth = SecurityContextHolder.getContext().getAuthentication();
    boolean anonymous = auth == null || !auth.isAuthenticated() || TRUST.isAnonymous(auth);
    ErrorCode code = anonymous ? ErrorCode.UNAUTHENTICATED : ErrorCode.FORBIDDEN;
    return respond(code, null, List.of(), request);
  }

  @ExceptionHandler(AuthenticationException.class)
  ResponseEntity<ErrorResponse> unauthenticated(
      AuthenticationException e, HttpServletRequest request) {
    return respond(ErrorCode.UNAUTHENTICATED, null, List.of(), request);
  }

  @ExceptionHandler(MethodArgumentNotValidException.class)
  ResponseEntity<ErrorResponse> invalidBody(
      MethodArgumentNotValidException e, HttpServletRequest request) {
    List<FieldIssue> details =
        e.getBindingResult().getFieldErrors().stream()
            .map(f -> new FieldIssue(f.getField(), f.getDefaultMessage()))
            .toList();
    return respond(ErrorCode.VALIDATION_ERROR, null, details, request);
  }

  @ExceptionHandler(HandlerMethodValidationException.class)
  ResponseEntity<ErrorResponse> invalidParameters(
      HandlerMethodValidationException e, HttpServletRequest request) {
    List<FieldIssue> details =
        e.getParameterValidationResults().stream()
            .flatMap(r ->
                r.getResolvableErrors().stream()
                    .map(err ->
                        new FieldIssue(
                            r.getMethodParameter().getParameterName(), err.getDefaultMessage())))
            .toList();
    return respond(ErrorCode.VALIDATION_ERROR, null, details, request);
  }

  @ExceptionHandler(ConstraintViolationException.class)
  ResponseEntity<ErrorResponse> constraint(
      ConstraintViolationException e, HttpServletRequest request) {
    List<FieldIssue> details =
        e.getConstraintViolations().stream()
            .map(v -> new FieldIssue(v.getPropertyPath().toString(), v.getMessage()))
            .toList();
    return respond(ErrorCode.VALIDATION_ERROR, null, details, request);
  }

  @ExceptionHandler({
    HttpMessageNotReadableException.class,
    MethodArgumentTypeMismatchException.class,
    MissingServletRequestParameterException.class,
    MissingServletRequestPartException.class
  })
  ResponseEntity<ErrorResponse> unreadable(Exception e, HttpServletRequest request) {
    return respond(ErrorCode.VALIDATION_ERROR, null, List.of(), request);
  }

  @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
  ResponseEntity<ErrorResponse> mediaType(Exception e, HttpServletRequest request) {
    return respond(ErrorCode.UNSUPPORTED_MEDIA_TYPE, null, List.of(), request);
  }

  @ExceptionHandler(MaxUploadSizeExceededException.class)
  ResponseEntity<ErrorResponse> tooLarge(Exception e, HttpServletRequest request) {
    return respond(ErrorCode.PAYLOAD_TOO_LARGE, null, List.of(), request);
  }

  @ExceptionHandler({NoResourceFoundException.class})
  ResponseEntity<ErrorResponse> noRoute(Exception e, HttpServletRequest request) {
    return respond(ErrorCode.NOT_FOUND, null, List.of(), request);
  }

  @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
  ResponseEntity<ErrorResponse> method(Exception e, HttpServletRequest request) {
    return respond(ErrorCode.NOT_FOUND, null, List.of(), request);
  }

  @ExceptionHandler(Exception.class)
  ResponseEntity<ErrorResponse> unexpected(Exception e, HttpServletRequest request) {
    log.error("Lỗi không lường trước", e);
    return respond(ErrorCode.INTERNAL_ERROR, null, List.of(), request);
  }

  private static ResponseEntity<ErrorResponse> respond(
      ErrorCode code, String message, List<FieldIssue> details, HttpServletRequest request) {
    return ResponseEntity.status(code.status()).body(body(code, message, details, request));
  }

  private static ErrorResponse body(
      ErrorCode code, String message, List<FieldIssue> details, HttpServletRequest request) {
    return new ErrorResponse(
        code.name(),
        message != null ? message : code.defaultMessage(),
        details,
        RequestIdFilter.current(request));
  }
}
