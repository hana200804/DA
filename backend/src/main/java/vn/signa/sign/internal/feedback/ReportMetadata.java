package vn.signa.sign.internal.feedback;

import java.util.List;
import tools.jackson.databind.JsonNode;
import vn.signa.shared.error.ApiException;
import vn.signa.shared.error.ErrorCode;
import vn.signa.shared.error.FieldIssue;
import vn.signa.sign.internal.feedback.ReportService.Reason;

public record ReportMetadata(Reason reason, String description) {

  public static ReportMetadata read(JsonNode json) {
    if (json == null || !json.isObject()) {
      throw invalid("metadata", "phải là một đối tượng JSON");
    }
    var reasonNode = json.path("reason");
    Reason reason;
    try {
      reason = Reason.valueOf(reasonNode.isString() ? reasonNode.asString() : "");
    } catch (IllegalArgumentException e) {
      throw invalid("reason", "phải là NOT_EXIST, WRONG_TYPE, WRONG_LOCATION hoặc OTHER");
    }
    var descriptionNode = json.path("description");
    if (!descriptionNode.isMissingNode()
        && !descriptionNode.isNull()
        && !descriptionNode.isString()) {
      throw invalid("description", "phải là chuỗi");
    }
    String description = descriptionNode.isString() ? descriptionNode.asString() : null;
    if (reason == Reason.OTHER && (description == null || description.isBlank())) {
      throw invalid("description", "bắt buộc khi lý do là OTHER");
    }
    if (description != null && description.codePointCount(0, description.length()) > 1000) {
      throw invalid("description", "tối đa 1000 ký tự");
    }
    return new ReportMetadata(reason, description);
  }

  private static ApiException invalid(String field, String message) {
    return new ApiException(
        ErrorCode.VALIDATION_ERROR,
        ErrorCode.VALIDATION_ERROR.defaultMessage(),
        List.of(new FieldIssue(field, message)),
        null);
  }
}
