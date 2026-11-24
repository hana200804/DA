package vn.signa.sign.map;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Chèn nhóm, loại và biển bằng SQL cho test bản đồ. Mã mang hậu tố ngẫu nhiên để không trùng với dữ
 * liệu của test khác trong cùng CSDL; {@code signa_app} không có quyền {@code DELETE} trên {@code
 * signs}, nên dữ liệu được giữ lại.
 */
final class MapFixtures {

  private static final String INSERT_SIGN =
      "INSERT INTO signs (id, sign_type_code, category_code, location, heading_deg, status,"
          + " confidence, confirm_count, deny_count, cover_crop_key, created_at, updated_at)"
          + " VALUES (?, ?, ?, ST_GeomFromText(?, 4326, 'axis-order=long-lat'), ?, ?, ?, ?, ?, ?,"
          + " UTC_TIMESTAMP(3), UTC_TIMESTAMP(3))";

  private final JdbcTemplate jdbc;
  private final String suffix =
      UUID.randomUUID().toString().replace("-", "").substring(0, 10).toUpperCase();

  MapFixtures(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /** Mã nhóm duy nhất, ≤ 20 ký tự. */
  String category(String name) {
    String code = name + suffix;
    jdbc.update(
        "INSERT INTO sign_categories (code, name, sort_order) VALUES (?, 'Nhóm thử', 99)", code);
    return code;
  }

  /** Mã loại duy nhất, ≤ 30 ký tự. */
  String type(String name, String category) {
    String code = name + "." + suffix;
    jdbc.update(
        "INSERT INTO sign_types (code, category_code, name, created_at, updated_at)"
            + " VALUES (?, ?, 'Loại thử', UTC_TIMESTAMP(3), UTC_TIMESTAMP(3))",
        code,
        category);
    return code;
  }

  String sign(String type, String category, double lat, double lng, String status) {
    return sign(type, category, lat, lng, status, null);
  }

  String sign(
      String type, String category, double lat, double lng, String status, String coverKey) {
    String id = UUID.randomUUID().toString();
    jdbc.update(INSERT_SIGN, row(id, type, category, lat, lng, status, coverKey));
    return id;
  }

  /**
   * Lưới {@code cols × rows} biển {@code APPROVED} bắt đầu ở góc tây nam, cách nhau {@code step}.
   */
  List<String> grid(
      String type, String category, double lat, double lng, int cols, int rows, double step) {
    List<String> ids = new ArrayList<>();
    List<Object[]> rowsArgs = new ArrayList<>();
    for (int r = 0; r < rows; r++) {
      for (int c = 0; c < cols; c++) {
        String id = UUID.randomUUID().toString();
        ids.add(id);
        rowsArgs.add(row(id, type, category, lat + r * step, lng + c * step, "APPROVED", null));
      }
    }
    jdbc.batchUpdate(INSERT_SIGN, rowsArgs);
    return ids;
  }

  private static Object[] row(
      String id,
      String type,
      String category,
      double lat,
      double lng,
      String status,
      String coverKey) {
    return new Object[] {
      id, type, category, "POINT(" + lng + " " + lat + ")", 90, status, 0.75, 3, 1, coverKey
    };
  }
}
