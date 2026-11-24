package vn.signa.catalog.internal;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Kiểm tra nội dung danh mục nhãn trước khi nạp. Gom mọi lỗi rồi ném một {@link
 * LabelsFileException}, mỗi lỗi ghi số dòng. Giới hạn khớp cột của {@code sign_categories}, {@code
 * sign_types} (docs/06 §5) và {@code web-admin/src/catalogTypes.ts}.
 */
public final class LabelMapValidator {

  static final Pattern CATEGORY_CODE = Pattern.compile("^[A-Z][A-Z0-9_]{0,19}$");
  static final int MAX_LABEL_ID = Short.MAX_VALUE;
  static final int MAX_CODE = 30;
  static final int MAX_CATEGORY_NAME = 100;
  static final int MAX_TYPE_NAME = 200;

  private LabelMapValidator() {}

  public static LabelMap validate(LabelMap map, String source) {
    List<String> problems = new ArrayList<>();

    if (map.version() == null || map.version() < 1) {
      problems.add("dòng 1: `version` phải là số nguyên dương");
    }
    if (map.categories().isEmpty()) problems.add("dòng 1: `categories` rỗng");
    if (map.classes().isEmpty()) problems.add("dòng 1: `classes` rỗng");

    Map<String, Integer> categoryLines = new HashMap<>();
    for (LabelMap.Category c : map.categories()) {
      String at = "dòng " + c.line() + ": ";
      Integer first = categoryLines.putIfAbsent(c.key(), c.line());
      if (first != null) problems.add(at + "nhóm `" + c.key() + "` trùng với dòng " + first);
      if (!CATEGORY_CODE.matcher(c.code()).matches()) {
        problems.add(at + "mã nhóm `" + c.code() + "` phải khớp " + CATEGORY_CODE.pattern());
      }
      if (c.name() == null) {
        problems.add(at + "nhóm `" + c.key() + "` thiếu `name`");
      } else if (c.name().length() > MAX_CATEGORY_NAME) {
        problems.add(at + "tên nhóm dài quá " + MAX_CATEGORY_NAME + " ký tự");
      }
    }

    Map<Integer, Integer> idLines = new HashMap<>();
    Map<String, Integer> codeLines = new HashMap<>();
    for (LabelMap.LabelClass c : map.classes()) {
      String at = "dòng " + c.line() + ": ";
      if (c.id() == null) {
        problems.add(at + "lớp thiếu `id`");
      } else if (c.id() < 0 || c.id() > MAX_LABEL_ID) {
        problems.add(at + "`id` " + c.id() + " phải trong 0.." + MAX_LABEL_ID);
      } else {
        Integer first = idLines.putIfAbsent(c.id(), c.line());
        if (first != null) problems.add(at + "`id` " + c.id() + " trùng với dòng " + first);
      }
      if (c.code() == null) {
        problems.add(at + "lớp thiếu `code`");
      } else if (c.code().length() > MAX_CODE) {
        problems.add(at + "`code` dài quá " + MAX_CODE + " ký tự");
      } else {
        Integer first = codeLines.putIfAbsent(c.code(), c.line());
        if (first != null) problems.add(at + "`code` " + c.code() + " trùng với dòng " + first);
      }
      if (c.categoryKey() == null) {
        problems.add(at + "lớp thiếu `category`");
      } else if (!categoryLines.containsKey(c.categoryKey())) {
        problems.add(at + "nhóm `" + c.categoryKey() + "` không có trong `categories`");
      }
      if (c.name() == null) {
        problems.add(at + "lớp thiếu `name`");
      } else if (c.name().length() > MAX_TYPE_NAME) {
        problems.add(at + "tên dài quá " + MAX_TYPE_NAME + " ký tự");
      }
      if (c.speedKmh() != null && (c.speedKmh() <= 0 || c.speedKmh() > Short.MAX_VALUE)) {
        problems.add(at + "`speed_kmh` phải là số nguyên dương");
      }
    }

    if (!problems.isEmpty()) throw new LabelsFileException(source, problems);
    return map;
  }
}
