package vn.signa.shared.error;

import java.util.List;

/** Lỗi nghiệp vụ được trả về client theo định dạng chung. */
public class ApiException extends RuntimeException {

  private final ErrorCode code;
  private final List<FieldIssue> details;
  private final Integer retryAfterSeconds;

  public ApiException(ErrorCode code) {
    this(code, code.defaultMessage(), List.of(), null);
  }

  public ApiException(ErrorCode code, String message) {
    this(code, message, List.of(), null);
  }

  public ApiException(
      ErrorCode code, String message, List<FieldIssue> details, Integer retryAfterSeconds) {
    super(message);
    this.code = code;
    this.details = List.copyOf(details);
    this.retryAfterSeconds = retryAfterSeconds;
  }

  public static ApiException notFound() {
    return new ApiException(ErrorCode.NOT_FOUND);
  }

  public static ApiException rateLimited(int retryAfterSeconds) {
    return new ApiException(
        ErrorCode.RATE_LIMITED,
        ErrorCode.RATE_LIMITED.defaultMessage(),
        List.of(),
        retryAfterSeconds);
  }

  public ErrorCode code() {
    return code;
  }

  public List<FieldIssue> details() {
    return details;
  }

  public Integer retryAfterSeconds() {
    return retryAfterSeconds;
  }
}
