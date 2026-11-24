package vn.signa.catalog.internal;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
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

  record AdminTypeRow(TypeRow type, Instant updatedAt) {}

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
        .query(CatalogRepository::category)
        .list();
  }

  boolean categoryExists(String code) {
    return jdbc.sql("SELECT code FROM sign_categories WHERE code = :code")
        .param("code", code)
        .query(String.class)
        .optional()
        .isPresent();
  }

  Optional<CategoryRow> lockCategory(String code) {
    return jdbc.sql(
            "SELECT code, name, description, sort_order FROM sign_categories"
                + " WHERE code = :code FOR UPDATE")
        .param("code", code)
        .query(CatalogRepository::category)
        .optional();
  }

  public void insertCategory(CategoryRow row) {
    jdbc.sql(
            "INSERT INTO sign_categories (code, name, description, sort_order)"
                + " VALUES (:code, :name, :description, :sortOrder)")
        .paramSource(row)
        .update();
  }

  void updateCategory(CategoryRow row) {
    jdbc.sql(
            """
            UPDATE sign_categories SET name = :name, description = :description,
                sort_order = :sortOrder WHERE code = :code
            """)
        .paramSource(row)
        .update();
  }

  long generation() {
    return jdbc.sql("SELECT generation FROM catalog_generation WHERE id = 1")
        .query(Long.class)
        .single();
  }

  void advanceGeneration() {
    jdbc.sql("UPDATE catalog_generation SET generation = generation + 1 WHERE id = 1").update();
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

  List<AdminTypeRow> adminTypes(Boolean active) {
    var query =
        jdbc.sql(
            "SELECT "
                + TYPE_COLUMNS
                + ", updated_at FROM sign_types"
                + (active == null ? "" : " WHERE active = :active")
                + " ORDER BY code");
    if (active != null) query.param("active", active);
    return query.query(CatalogRepository::adminType).list();
  }

  Optional<AdminTypeRow> lockType(String code) {
    return jdbc.sql(
            "SELECT " + TYPE_COLUMNS + ", updated_at FROM sign_types WHERE code = :code FOR UPDATE")
        .param("code", code)
        .query(CatalogRepository::adminType)
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
        .paramSource(typeParameters(row, now))
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
        .paramSource(typeParameters(row, now))
        .update();
  }

  void updateAdminFields(TypeRow row, Instant now) {
    jdbc.sql(
            """
            UPDATE sign_types
               SET category_code = :categoryCode, name = :name, description = :description,
                   speed_kmh = :speedKmh, active = :active, updated_at = :now
             WHERE code = :code
            """)
        .paramSource(typeParameters(row, now))
        .update();
  }

  void updateIcon(String code, String key, Instant now) {
    jdbc.sql("UPDATE sign_types SET icon_key = :key, updated_at = :now WHERE code = :code")
        .param("key", key)
        .param("now", Timestamp.from(now))
        .param("code", code)
        .update();
  }

  private static MapSqlParameterSource typeParameters(TypeRow row, Instant now) {
    return new MapSqlParameterSource("code", row.code())
        .addValue("categoryCode", row.categoryCode())
        .addValue("name", row.name())
        .addValue("description", row.description())
        .addValue("speedKmh", row.speedKmh())
        .addValue("iconKey", row.iconKey())
        .addValue("labelId", row.labelId())
        .addValue("labelMapVersion", row.labelMapVersion())
        .addValue("labelName", row.labelName())
        .addValue("active", row.active())
        .addValue("now", Timestamp.from(now));
  }

  private static CategoryRow category(ResultSet rs, int n) throws SQLException {
    return new CategoryRow(
        rs.getString("code"),
        rs.getString("name"),
        rs.getString("description"),
        rs.getInt("sort_order"));
  }

  private static AdminTypeRow adminType(ResultSet rs, int n) throws SQLException {
    return new AdminTypeRow(type(rs, n), rs.getTimestamp("updated_at").toInstant());
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
