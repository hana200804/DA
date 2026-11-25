package vn.signa.catalog.internal;

import static java.nio.charset.StandardCharsets.UTF_8;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Locale;
import tools.jackson.databind.JsonNode;
import vn.signa.shared.error.ApiException;
import vn.signa.shared.error.ErrorCode;
import vn.signa.shared.error.FieldIssue;

/** Dữ liệu đầu vào của API quản trị danh mục (docs/07 §8). */
public final class CatalogAdminRequests {

  private static final String CATEGORY_CODE = "^[A-Z][A-Z0-9_]{0,19}$";
  private static final int MAX_DESCRIPTION_BYTES = 65_535;

  private CatalogAdminRequests() {}

  public record NewCategory(
      @NotBlank @Pattern(regexp = CATEGORY_CODE) String code,
      @NotBlank @Size(max = 100) String name,
      String description,
      @NotNull @Min(Short.MIN_VALUE) @Max(Short.MAX_VALUE) Integer sortOrder) {
    public NewCategory {
      code = normalizeCategoryCode(code);
      name = trim(name);
    }
  }

  public record EditCategory(
      @Size(min = 1, max = 100) String name,
      @Schema(type = "string", nullable = true) JsonNode description,
      @Min(Short.MIN_VALUE) @Max(Short.MAX_VALUE) Integer sortOrder) {
    public EditCategory {
      name = trim(name);
    }

    String descriptionOr(String current) {
      return patchDescription(description, current);
    }
  }

  static final String SIGN_TYPE_CODE = "[A-Za-z0-9][A-Za-z0-9._~-]{0,29}";

  public record NewType(
      @NotBlank @Pattern(regexp = SIGN_TYPE_CODE) String code,
      @NotBlank @Pattern(regexp = CATEGORY_CODE) String categoryCode,
      @NotBlank @Size(max = 200) String name,
      String description,
      @Min(1) @Max(Short.MAX_VALUE) Integer speedKmh,
      Boolean active) {
    public NewType {
      code = trim(code);
      categoryCode = normalizeCategoryCode(categoryCode);
      name = trim(name);
    }
  }

  public record EditType(
      @Pattern(regexp = CATEGORY_CODE) String categoryCode,
      @Size(min = 1, max = 200) String name,
      @Schema(type = "string", nullable = true) JsonNode description,
      @Schema(type = "integer", minimum = "1", maximum = "32767", nullable = true)
          JsonNode speedKmh,
      Boolean active) {
    public EditType {
      categoryCode = normalizeCategoryCode(categoryCode);
      name = trim(name);
    }

    String descriptionOr(String current) {
      return patchDescription(description, current);
    }

    Integer speedOr(Integer current) {
      if (omitted(speedKmh)) return current;
      if (speedKmh.isNull()) return null;
      if (!speedKmh.isIntegralNumber()
          || !speedKmh.canConvertToInt()
          || speedKmh.intValue() < 1
          || speedKmh.intValue() > Short.MAX_VALUE) {
        throw invalid("speedKmh", "phải là số nguyên từ 1 đến 32767");
      }
      return speedKmh.intValue();
    }
  }

  static String categoryCode(String raw) {
    String code = normalizeCategoryCode(raw);
    if (!code.matches(CATEGORY_CODE)) {
      throw invalid("code", "phải bắt đầu bằng chữ cái và khớp " + CATEGORY_CODE);
    }
    return code;
  }

  static String typeCode(String raw) {
    String code = raw.trim();
    if (code.isBlank() || code.length() > 30) {
      throw invalid("code", "phải có từ 1 đến 30 ký tự");
    }
    return code;
  }

  static String description(String raw) {
    String value = trim(raw);
    if (value == null || value.isEmpty()) return null;
    if (value.getBytes(UTF_8).length > MAX_DESCRIPTION_BYTES) {
      throw invalid("description", "vượt quá 65535 byte UTF-8");
    }
    return value;
  }

  static ApiException invalid(String field, String message) {
    return new ApiException(
        ErrorCode.VALIDATION_ERROR,
        ErrorCode.VALIDATION_ERROR.defaultMessage(),
        List.of(new FieldIssue(field, message)),
        null);
  }

  private static String patchDescription(JsonNode value, String current) {
    if (omitted(value)) return current;
    if (value.isNull()) return null;
    if (!value.isString()) throw invalid("description", "phải là chuỗi");
    return description(value.asString());
  }

  private static boolean omitted(JsonNode value) {
    return value == null || value.isMissingNode();
  }

  private static String normalizeCategoryCode(String code) {
    return code == null ? null : code.trim().toUpperCase(Locale.ROOT);
  }

  private static String trim(String value) {
    return value == null ? null : value.trim();
  }
}
