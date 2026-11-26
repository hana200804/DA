package vn.signa.shared.web;

import java.util.List;
import vn.signa.shared.error.FieldIssue;

/** Thân lỗi chung theo docs/07 §2. */
public record ErrorResponse(
    String code, String message, List<FieldIssue> details, String requestId) {}
