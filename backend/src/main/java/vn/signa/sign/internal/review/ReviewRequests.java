package vn.signa.sign.internal.review;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import tools.jackson.databind.JsonNode;
import vn.signa.shared.error.ApiException;
import vn.signa.shared.error.ErrorCode;
import vn.signa.shared.error.FieldIssue;
import vn.signa.sign.clustering.GeoPoint;

/** Thân request của API phê duyệt và sửa biển (docs/07 §8). */
public final class ReviewRequests {

  /** Độ dài cột {@code signs.sign_type_code}. */
  static final int MAX_CODE = 30;

  /** Độ dài lý do của kiểm duyệt viên ({@code audit_logs.reason}). */
  static final int MAX_REASON = 1000;

  private ReviewRequests() {}

  /** Toạ độ WGS84. */
  public record LocationInput(
      @NotNull @DecimalMin("-90") @DecimalMax("90") Double lat,
      @NotNull @DecimalMin("-180") @DecimalMax("180") Double lng) {

    GeoPoint toPoint() {
      return new GeoPoint(lat, lng);
    }
  }

  /** {@code POST /admin/signs/{id}/approve}. */
  public record Approve(
      @NotNull @Min(0) Integer version,
      @Size(min = 1, max = MAX_CODE) String signTypeCode,
      @Valid LocationInput location) {
    public Approve {
      signTypeCode = trim(signTypeCode);
    }

    SignChange change() {
      return new SignChange(signTypeCode, point(location), false, null);
    }
  }

  /** {@code POST /admin/signs/{id}/reject} và {@code /remove}. */
  public record Reasoned(
      @NotNull @Min(0) Integer version, @NotBlank @Size(max = MAX_REASON) String reason) {
    public Reasoned {
      reason = trim(reason);
    }
  }

  /**
   * {@code POST /admin/signs/{id}/keep}. {@code headingDeg} bỏ qua hoặc {@code null}: giữ hướng.
   */
  public record Keep(
      @NotNull @Min(0) Integer version,
      @Size(min = 1, max = MAX_CODE) String signTypeCode,
      @Valid LocationInput location,
      @Min(0) @Max(359) Integer headingDeg) {
    public Keep {
      signTypeCode = trim(signTypeCode);
    }

    SignChange change() {
      return new SignChange(signTypeCode, point(location), headingDeg != null, headingDeg);
    }
  }

  /**
   * {@code PATCH /admin/signs/{id}}. {@code headingDeg} bỏ qua thì giữ, {@code null} thì xoá hướng,
   * nên nhận dạng {@link JsonNode} để phân biệt hai trường hợp.
   */
  public record Edit(
      @NotNull @Min(0) Integer version,
      @Size(min = 1, max = MAX_CODE) String signTypeCode,
      @Valid LocationInput location,
      @Schema(type = "integer", minimum = "0", maximum = "359", nullable = true)
          JsonNode headingDeg) {
    public Edit {
      signTypeCode = trim(signTypeCode);
    }

    SignChange change() {
      boolean headingSet = headingDeg != null && !headingDeg.isMissingNode();
      Integer heading = headingSet ? heading(headingDeg) : null;
      SignChange change = new SignChange(signTypeCode, point(location), headingSet, heading);
      if (change.isEmpty()) {
        throw invalid("body", "cần ít nhất một trong signTypeCode, location, headingDeg");
      }
      return change;
    }

    private static Integer heading(JsonNode value) {
      if (value.isNull()) return null;
      if (!value.isIntegralNumber()
          || !value.canConvertToInt()
          || value.intValue() < 0
          || value.intValue() > 359) {
        throw invalid("headingDeg", "phải là số nguyên từ 0 đến 359 hoặc null");
      }
      return value.intValue();
    }
  }

  /** {@code POST /admin/signs}. */
  public record NewSign(
      @NotBlank @Size(max = MAX_CODE) String signTypeCode,
      @NotNull @Valid LocationInput location,
      @Min(0) @Max(359) Integer headingDeg) {
    public NewSign {
      signTypeCode = trim(signTypeCode);
    }
  }

  static ApiException invalid(String field, String message) {
    return new ApiException(
        ErrorCode.VALIDATION_ERROR,
        ErrorCode.VALIDATION_ERROR.defaultMessage(),
        List.of(new FieldIssue(field, message)),
        null);
  }

  private static GeoPoint point(LocationInput location) {
    return location == null ? null : location.toPoint();
  }

  private static String trim(String value) {
    return value == null ? null : value.strip();
  }
}
