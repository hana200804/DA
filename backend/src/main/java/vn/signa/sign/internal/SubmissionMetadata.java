package vn.signa.sign.internal;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import vn.signa.shared.error.ApiException;
import vn.signa.shared.error.ErrorCode;
import vn.signa.shared.error.FieldIssue;
import vn.signa.sign.clustering.GeoPoint;
import vn.signa.sign.clustering.SubmissionFlag;

record SubmissionMetadata(
    UUID clientId,
    String signTypeCode,
    BigDecimal confidence,
    GeoPoint location,
    Integer headingDeg,
    Source source,
    Set<SubmissionFlag> flags,
    String modelName,
    String modelVersion,
    int labelMapVersion,
    Instant capturedAt) {

  enum Source {
    CAMERA,
    PHOTO
  }

  private static final Instant MIN_CAPTURE = Instant.parse("1000-01-01T00:00:00Z");
  private static final Instant MAX_CAPTURE = Instant.parse("9999-12-31T23:59:59.999999999Z");

  static SubmissionMetadata read(JsonMapper mapper, byte[] json) {
    JsonNode root;
    try {
      root =
          json == null
              ? null
              : mapper
                  .reader()
                  .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                  .with(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
                  .readTree(json);
    } catch (JacksonException e) {
      throw invalid("metadata", "phải là JSON hợp lệ");
    }
    if (root == null || !root.isObject()) {
      throw invalid("metadata", "phải là một đối tượng JSON");
    }
    var validation = new Validation();
    UUID id = validation.uuid(root.path("clientId"));
    String code = validation.text(root.path("signTypeCode"), "signTypeCode", 30);
    BigDecimal confidence = validation.decimal(root.path("confidence"), "confidence", 0, 1);
    BigDecimal lat = validation.decimal(root.path("location").path("lat"), "location.lat", -90, 90);
    BigDecimal lng =
        validation.decimal(root.path("location").path("lng"), "location.lng", -180, 180);
    JsonNode headingNode = root.path("headingDeg");
    Integer heading =
        headingNode.isMissingNode() || headingNode.isNull()
            ? null
            : validation.integer(headingNode, "headingDeg", 0, 359);
    Source source = validation.enumeration(root.path("source"), "source", Source.class);
    Set<SubmissionFlag> flags = validation.flags(root.path("flags"));
    JsonNode model = root.path("model");
    String name = validation.text(model.path("name"), "model.name", 50);
    String version = validation.text(model.path("version"), "model.version", 50);
    int labelVersion =
        validation.integer(
            model.path("labelMapVersion"),
            "model.labelMapVersion",
            Integer.MIN_VALUE,
            Integer.MAX_VALUE);
    Instant capturedAt = validation.capturedAt(root.path("capturedAt"));
    if (!validation.issues.isEmpty()) {
      throw new ApiException(
          ErrorCode.VALIDATION_ERROR,
          ErrorCode.VALIDATION_ERROR.defaultMessage(),
          validation.issues,
          null);
    }
    return new SubmissionMetadata(
        id,
        code,
        confidence,
        new GeoPoint(lat.doubleValue(), lng.doubleValue()),
        heading,
        source,
        Set.copyOf(flags),
        name,
        version,
        labelVersion,
        capturedAt);
  }

  static ApiException invalid(String field, String issue) {
    return new ApiException(
        ErrorCode.VALIDATION_ERROR,
        ErrorCode.VALIDATION_ERROR.defaultMessage(),
        List.of(new FieldIssue(field, issue)),
        null);
  }

  private static final class Validation {
    private final List<FieldIssue> issues = new ArrayList<>();

    UUID uuid(JsonNode node) {
      if (node.isString()) {
        String value = node.asString();
        try {
          UUID id = UUID.fromString(value);
          if (id.toString().equalsIgnoreCase(value)) return id;
        } catch (IllegalArgumentException ignored) {
          // UUID.fromString cũng chấp nhận một số dạng rút gọn, nên so với dạng chuẩn.
        }
      }
      issues.add(new FieldIssue("clientId", "phải là UUID"));
      return null;
    }

    String text(JsonNode node, String field, int maxLength) {
      if (node.isString() && !node.asString().isBlank() && node.asString().length() <= maxLength) {
        return node.asString();
      }
      issues.add(
          new FieldIssue(field, "phải là chuỗi có nội dung, tối đa " + maxLength + " ký tự"));
      return null;
    }

    BigDecimal decimal(JsonNode node, String field, int min, int max) {
      if (node.isNumber()) {
        BigDecimal value = node.decimalValue();
        if (value.compareTo(BigDecimal.valueOf(min)) >= 0
            && value.compareTo(BigDecimal.valueOf(max)) <= 0) {
          return value;
        }
      }
      issues.add(new FieldIssue(field, "phải là số trong [" + min + ", " + max + "]"));
      return BigDecimal.ZERO;
    }

    int integer(JsonNode node, String field, int min, int max) {
      if (node.isIntegralNumber() && node.canConvertToInt()) {
        int value = node.intValue();
        if (value >= min && value <= max) return value;
      }
      issues.add(new FieldIssue(field, "phải là số nguyên trong [" + min + ", " + max + "]"));
      return 0;
    }

    <E extends Enum<E>> E enumeration(JsonNode node, String field, Class<E> type) {
      if (node.isString()) {
        try {
          return Enum.valueOf(type, node.asString());
        } catch (IllegalArgumentException ignored) {
          // Báo cùng lỗi cho giá trị lạ và sai kiểu.
        }
      }
      issues.add(new FieldIssue(field, "giá trị không được hỗ trợ"));
      return null;
    }

    Set<SubmissionFlag> flags(JsonNode node) {
      var flags = EnumSet.noneOf(SubmissionFlag.class);
      if (node.isArray()) {
        for (int i = 0; i < node.size(); i++) {
          SubmissionFlag flag = enumeration(node.get(i), "flags[" + i + "]", SubmissionFlag.class);
          if (flag != null) flags.add(flag);
        }
      } else if (!node.isMissingNode()) {
        issues.add(new FieldIssue("flags", "phải là mảng"));
      }
      return flags;
    }

    Instant capturedAt(JsonNode node) {
      if (node.isString()) {
        try {
          Instant instant = Instant.parse(node.asString());
          if (!instant.isBefore(MIN_CAPTURE) && !instant.isAfter(MAX_CAPTURE)) return instant;
        } catch (DateTimeParseException ignored) {
          // Báo cùng lỗi cho giá trị ngoài miền DATETIME và sai định dạng.
        }
      }
      issues.add(new FieldIssue("capturedAt", "phải là thời điểm ISO-8601 hợp lệ"));
      return null;
    }
  }
}
