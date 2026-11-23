package vn.signa.shared.error;

import org.springframework.http.HttpStatus;

/** Mã lỗi chung theo docs/07 §2. */
public enum ErrorCode {
  VALIDATION_ERROR(HttpStatus.BAD_REQUEST, "Dữ liệu không hợp lệ"),
  UNAUTHENTICATED(HttpStatus.UNAUTHORIZED, "Cần đăng nhập"),
  FORBIDDEN(HttpStatus.FORBIDDEN, "Không đủ quyền"),
  NOT_FOUND(HttpStatus.NOT_FOUND, "Không tìm thấy tài nguyên"),
  CONFLICT(HttpStatus.CONFLICT, "Xung đột dữ liệu"),
  SIGN_ALREADY_PROCESSED(HttpStatus.CONFLICT, "Biển đã được người khác xử lý"),
  PAYLOAD_TOO_LARGE(HttpStatus.CONTENT_TOO_LARGE, "Dữ liệu gửi lên quá lớn"),
  UNSUPPORTED_MEDIA_TYPE(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "Định dạng không được hỗ trợ"),
  BUSINESS_RULE(HttpStatus.UNPROCESSABLE_CONTENT, "Vi phạm quy tắc nghiệp vụ"),
  RATE_LIMITED(HttpStatus.TOO_MANY_REQUESTS, "Quá nhiều yêu cầu, vui lòng thử lại sau"),
  INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "Lỗi hệ thống"),
  RECOGNITION_UNAVAILABLE(
      HttpStatus.SERVICE_UNAVAILABLE, "Dịch vụ nhận diện tạm thời không phản hồi");

  private final HttpStatus status;
  private final String defaultMessage;

  ErrorCode(HttpStatus status, String defaultMessage) {
    this.status = status;
    this.defaultMessage = defaultMessage;
  }

  public HttpStatus status() {
    return status;
  }

  public String defaultMessage() {
    return defaultMessage;
  }
}
