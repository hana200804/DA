package vn.signa.sign.internal.review;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import vn.signa.shared.error.ApiException;
import vn.signa.shared.error.ErrorCode;
import vn.signa.shared.error.FieldIssue;
import vn.signa.sign.clustering.SignStatus;
import vn.signa.sign.clustering.Suggestion;
import vn.signa.sign.internal.map.BoundingBox;
import vn.signa.sign.internal.map.MapQueryParser;

/** Tham số của {@code GET /admin/review-queue}. Mọi lỗi gom vào một {@code 400}. */
public final class ReviewQueries {

  public static final int DEFAULT_SIZE = 20;
  public static final int MAX_SIZE = 100;
  static final int MAX_CATEGORIES = 50;
  static final int MAX_CATEGORY_LENGTH = 20;

  /** Trạng thái chờ kiểm duyệt. */
  static final Set<SignStatus> REVIEWABLE = Set.of(SignStatus.PENDING, SignStatus.UNDER_REVIEW);

  private ReviewQueries() {}

  /**
   * Bộ lọc đã kiểm.
   *
   * @param statuses {@code PENDING}, {@code UNDER_REVIEW} hoặc cả hai
   * @param suggestion {@code null} nếu không lọc
   * @param bbox {@code null} nếu không lọc
   * @param page trang tính từ 0
   */
  public record QueueQuery(
      Set<SignStatus> statuses,
      Suggestion suggestion,
      List<String> categories,
      BoundingBox bbox,
      int page,
      int size) {
    long offset() {
      return (long) page * size;
    }
  }

  public static QueueQuery parse(
      String status, String suggestion, String categories, String bbox, String page, String size) {
    List<FieldIssue> issues = new ArrayList<>();
    Set<SignStatus> statuses = parseStatus(status, issues);
    Suggestion parsedSuggestion = parseSuggestion(suggestion, issues);
    List<String> cats = parseCategories(categories, issues);
    BoundingBox box = parseBbox(bbox, issues);
    int p = parseInt("page", page, 0, 0, Integer.MAX_VALUE / MAX_SIZE, issues);
    int s = parseInt("size", size, DEFAULT_SIZE, 1, MAX_SIZE, issues);
    if (!issues.isEmpty()) {
      throw new ApiException(
          ErrorCode.VALIDATION_ERROR, ErrorCode.VALIDATION_ERROR.defaultMessage(), issues, null);
    }
    return new QueueQuery(statuses, parsedSuggestion, cats, box, p, s);
  }

  private static Set<SignStatus> parseStatus(String raw, List<FieldIssue> issues) {
    if (raw == null || raw.isBlank()) return REVIEWABLE;
    String value = raw.strip().toUpperCase(Locale.ROOT);
    if (value.equals("PENDING")) return Set.of(SignStatus.PENDING);
    if (value.equals("UNDER_REVIEW")) return Set.of(SignStatus.UNDER_REVIEW);
    issues.add(new FieldIssue("status", "phải là PENDING hoặc UNDER_REVIEW"));
    return REVIEWABLE;
  }

  private static Suggestion parseSuggestion(String raw, List<FieldIssue> issues) {
    if (raw == null || raw.isBlank()) return null;
    try {
      return Suggestion.valueOf(raw.strip().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException e) {
      issues.add(new FieldIssue("suggestion", "phải là APPROVE, INSPECT hoặc WAIT"));
      return null;
    }
  }

  private static List<String> parseCategories(String raw, List<FieldIssue> issues) {
    if (raw == null) return List.of();
    Set<String> codes = new LinkedHashSet<>();
    for (String part : raw.split(",")) {
      String code = part.strip();
      if (!code.isEmpty()) codes.add(code);
    }
    if (codes.size() > MAX_CATEGORIES) {
      issues.add(new FieldIssue("categories", "tối đa " + MAX_CATEGORIES + " mục"));
      return List.of();
    }
    if (codes.stream().anyMatch(c -> c.length() > MAX_CATEGORY_LENGTH)) {
      issues.add(
          new FieldIssue("categories", "mỗi mã dài tối đa " + MAX_CATEGORY_LENGTH + " ký tự"));
      return List.of();
    }
    return List.copyOf(codes);
  }

  /** Dùng lại luật {@code bbox} của bản đồ (lát 4). */
  private static BoundingBox parseBbox(String raw, List<FieldIssue> issues) {
    if (raw == null || raw.isBlank()) return null;
    try {
      return MapQueryParser.parse(raw, null, null, null).bbox();
    } catch (ApiException e) {
      issues.addAll(e.details());
      return null;
    }
  }

  private static int parseInt(
      String field, String raw, int fallback, int min, int max, List<FieldIssue> issues) {
    if (raw == null || raw.isBlank()) return fallback;
    try {
      int value = Integer.parseInt(raw.strip());
      if (value >= min && value <= max) return value;
    } catch (NumberFormatException e) {
      // báo lỗi bên dưới
    }
    issues.add(new FieldIssue(field, "phải là số nguyên từ " + min + " tới " + max));
    return fallback;
  }
}
