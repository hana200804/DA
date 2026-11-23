package vn.signa.catalog.internal;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** SQL trên {@code sign_categories}, {@code sign_types} (docs/06 §5). */
@Repository
public class CatalogRepository {

  /** Một dòng {@code sign_categories}. */
  public record CategoryRow(String code, String name, String description, int sortOrder) {}

  /** Một dòng {@code sign_types}, trừ {@code created_at}, {@code updated_at}. */
  public record TypeRow(
      String code,
      String categoryCode,
      String name,
      String description,
      Integer speedKmh,
      String iconKey,
      Integer labelId,
      Integer labelMapVersion,
      String labelName,
      boolean active) {}

  private static final String TYPE_COLUMNS =
      """
      code, category_code, name, description, speed_kmh, icon_key,
      label_id, label_map_version, label_name, active\
      """;

  private final JdbcClient jdbc;

  CatalogRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  public List<CategoryRow> categories() {
    return jdbc.sql(
            "SELECT code, name, description, sort_order FROM sign_categories"
                + " ORDER BY sort_order, code")
        .query((rs, n) ->
            new CategoryRow(
                rs.getString("code"),
                rs.getString("name"),
                rs.getString("description"),
                rs.getInt("sort_order")))
        .list();
  }

  public void insertCategory(CategoryRow row) {
    jdbc.sql(
            "INSERT INTO sign_categories (code, name, description, sort_order)"
                + " VALUES (:code, :name, :description, :sortOrder)")
        .paramSource(row)
        .update();
  }

  public List<TypeRow> allTypes() {
    return jdbc.sql("SELECT " + TYPE_COLUMNS + " FROM sign_types ORDER BY code")
        .query(CatalogRepository::type)
        .list();
  }

  public List<TypeRow> activeTypes() {
    return jdbc.sql("SELECT " + TYPE_COLUMNS + " FROM sign_types WHERE active ORDER BY code")
        .query(CatalogRepository::type)
        .list();
  }

  public Optional<TypeRow> type(String code) {
    return jdbc.sql("SELECT " + TYPE_COLUMNS + " FROM sign_types WHERE code = :code")
        .param("code", code)
        .query(CatalogRepository::type)
        .optional();
  }

  /** Phiên bản danh mục nhãn của các loại đang dùng và nhận diện được. */
  public Set<Integer> recognizableVersions() {
    return new TreeSet<>(
        jdbc.sql(
                "SELECT DISTINCT label_map_version FROM sign_types"
                    + " WHERE active AND label_id IS NOT NULL AND label_map_version IS NOT NULL")
            .query(Integer.class)
            .list());
  }

  public void insertType(TypeRow row, Instant now) {
    jdbc.sql(
            "INSERT INTO sign_types ("
                + TYPE_COLUMNS
                + ", created_at, updated_at) VALUES (:code, :categoryCode, :name, :description,"
                + " :speedKmh, :iconKey, :labelId, :labelMapVersion, :labelName, :active, :now,"
                + " :now)")
        .paramSource(row)
        .param("now", Timestamp.from(now))
        .update();
  }

  /** Ghi các trường do danh mục nhãn quản lý; không đụng mô tả, ảnh, {@code active}. */
  public void updateLabelFields(TypeRow row, Instant now) {
    jdbc.sql(
            """
            UPDATE sign_types
               SET category_code = :categoryCode, name = :name, speed_kmh = :speedKmh,
                   label_id = :labelId, label_map_version = :labelMapVersion,
                   label_name = :labelName, updated_at = :now
             WHERE code = :code\
            """)
        .paramSource(row)
        .param("now", Timestamp.from(now))
        .update();
  }

  private static TypeRow type(ResultSet rs, int n) throws SQLException {
    return new TypeRow(
        rs.getString("code"),
        rs.getString("category_code"),
        rs.getString("name"),
        rs.getString("description"),
        rs.getObject("speed_kmh", Integer.class),
        rs.getString("icon_key"),
        rs.getObject("label_id", Integer.class),
        rs.getObject("label_map_version", Integer.class),
        rs.getString("label_name"),
        rs.getBoolean("active"));
  }
}
