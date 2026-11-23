package vn.signa.sign.internal.map;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import vn.signa.shared.error.ApiException;
import vn.signa.shared.error.ErrorCode;
import vn.signa.shared.error.FieldIssue;

/**
 * Đọc tham số của {@code GET /signs}. Mọi lỗi được gom vào một {@code 400 VALIDATION_ERROR} với
 * {@code details[].field} là tên tham số.
 */
public final class MapQueryParser {

  public static final int MIN_ZOOM = 0;
  public static final int MAX_ZOOM = 22;
  public static final int DEFAULT_ZOOM = 14;
  public static final int MAX_FILTER_ITEMS = 50;

  /** Độ dài cột {@code signs.category_code} và {@code signs.sign_type_code}. */
  static final int MAX_CATEGORY_LENGTH = 20;

  static final int MAX_SIGN_TYPE_LENGTH = 30;

  /**
   * Số thập phân, cho phép số mũ vì {@code Number.toString} của JavaScript có thể ra {@code 1e-7}.
   */
  private static final Pattern NUMBER =
      Pattern.compile("[+-]?(\\d+(\\.\\d*)?|\\.\\d+)([eE][+-]?\\d{1,3})?");

  private static final Pattern INTEGER = Pattern.compile("[+-]?\\d{1,3}");

  private MapQueryParser() {}

  public static MapQuery parse(String bbox, String categories, String signTypeCodes, String zoom) {
    List<FieldIssue> issues = new ArrayList<>();
    BoundingBox box = parseBbox(bbox, issues);
    int z = parseZoom(zoom, issues);
    List<String> cats = parseCodes("categories", categories, MAX_CATEGORY_LENGTH, issues);
    List<String> types = parseCodes("signTypeCodes", signTypeCodes, MAX_SIGN_TYPE_LENGTH, issues);
    if (!issues.isEmpty()) {
      throw new ApiException(
          ErrorCode.VALIDATION_ERROR, ErrorCode.VALIDATION_ERROR.defaultMessage(), issues, null);
    }
    return new MapQuery(box, cats, types, z);
  }

  static BoundingBox parseBbox(String raw, List<FieldIssue> issues) {
    if (raw == null || raw.isBlank()) {
      issues.add(new FieldIssue("bbox", "bắt buộc, dạng minLng,minLat,maxLng,maxLat"));
      return null;
    }
    String[] parts = raw.split(",", -1);
    if (parts.length != 4) {
      issues.add(new FieldIssue("bbox", "phải gồm đúng 4 số minLng,minLat,maxLng,maxLat"));
      return null;
    }
    double[] v = new double[4];
    for (int i = 0; i < 4; i++) {
      String part = parts[i].trim();
      if (!NUMBER.matcher(part).matches()) {
        issues.add(new FieldIssue("bbox", "phần tử thứ " + (i + 1) + " không phải số"));
        return null;
      }
      v[i] = Double.parseDouble(part);
    }
    double minLng = v[0];
    double minLat = v[1];
    double maxLng = v[2];
    double maxLat = v[3];
    int before = issues.size();
    if (!inRange(minLng, 180) || !inRange(maxLng, 180)) {
      issues.add(new FieldIssue("bbox", "kinh độ phải nằm trong [-180, 180]"));
    }
    if (!inRange(minLat, 90) || !inRange(maxLat, 90)) {
      issues.add(new FieldIssue("bbox", "vĩ độ phải nằm trong [-90, 90]"));
    }
    if (issues.size() > before) return null;

    if (minLng > maxLng) {
      issues.add(
          new FieldIssue(
              "bbox", "vùng vắt qua kinh tuyến 180° chưa được hỗ trợ, hãy tách thành hai vùng"));
    } else if (minLng == maxLng) {
      issues.add(new FieldIssue("bbox", "minLng phải nhỏ hơn maxLng"));
    }
    if (minLat >= maxLat) {
      issues.add(new FieldIssue("bbox", "minLat phải nhỏ hơn maxLat"));
    }
    return issues.size() > before ? null : new BoundingBox(minLng, minLat, maxLng, maxLat);
  }

  static int parseZoom(String raw, List<FieldIssue> issues) {
    if (raw == null || raw.isBlank()) return DEFAULT_ZOOM;
    String value = raw.trim();
    if (INTEGER.matcher(value).matches()) {
      int zoom = Integer.parseInt(value);
      if (zoom >= MIN_ZOOM && zoom <= MAX_ZOOM) return zoom;
    }
    issues.add(new FieldIssue("zoom", "phải là số nguyên từ 0 tới 22"));
    return DEFAULT_ZOOM;
  }

  static List<String> parseCodes(String field, String raw, int maxLength, List<FieldIssue> issues) {
    if (raw == null) return List.of();
    Set<String> codes = new LinkedHashSet<>();
    for (String part : raw.split(",")) {
      String code = part.trim();
      if (!code.isEmpty()) codes.add(code);
    }
    if (codes.size() > MAX_FILTER_ITEMS) {
      issues.add(new FieldIssue(field, "tối đa " + MAX_FILTER_ITEMS + " mục"));
      return List.of();
    }
    if (codes.stream().anyMatch(c -> c.length() > maxLength)) {
      issues.add(new FieldIssue(field, "mỗi mã dài tối đa " + maxLength + " ký tự"));
      return List.of();
    }
    return List.copyOf(codes);
  }

  private static boolean inRange(double value, double limit) {
    return value >= -limit && value <= limit;
  }
}
