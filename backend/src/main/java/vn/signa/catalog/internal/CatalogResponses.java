package vn.signa.catalog.internal;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import vn.signa.catalog.api.SignTypeView;
import vn.signa.catalog.internal.CatalogRepository.AdminTypeRow;
import vn.signa.catalog.internal.CatalogRepository.CategoryRow;
import vn.signa.catalog.internal.CatalogRepository.TypeRow;

/** Dạng phản hồi của API danh mục (docs/07 §4, §8). */
public final class CatalogResponses {

  private CatalogResponses() {}

  public record SignCategoryItem(String code, String name, String description, int sortOrder) {
    static SignCategoryItem of(CategoryRow row) {
      return new SignCategoryItem(row.code(), row.name(), row.description(), row.sortOrder());
    }
  }

  public record SignCategoryList(List<SignCategoryItem> items) {}

  public record SignTypeItem(
      String code,
      String categoryCode,
      String name,
      String description,
      Integer speedKmh,
      String iconUrl,
      boolean recognizable) {
    static SignTypeItem of(TypeRow row) {
      return new SignTypeItem(
          row.code(),
          row.categoryCode(),
          row.name(),
          row.description(),
          row.speedKmh(),
          SignTypeView.iconUrl(row.iconKey()),
          row.labelId() != null);
    }
  }

  /**
   * @param labelMapVersion phiên bản danh mục nhãn lớn nhất trong các loại đang dùng, {@code null}
   *     nếu chưa nạp nhãn
   */
  public record SignTypeList(Integer labelMapVersion, List<SignTypeItem> items) {}

  public record AdminSignTypeItem(
      String code,
      String categoryCode,
      String name,
      String description,
      Integer speedKmh,
      String iconUrl,
      boolean recognizable,
      boolean active,
      Instant updatedAt) {
    static AdminSignTypeItem of(AdminTypeRow record) {
      TypeRow row = record.type();
      return new AdminSignTypeItem(
          row.code(),
          row.categoryCode(),
          row.name(),
          row.description(),
          row.speedKmh(),
          SignTypeView.iconUrl(row.iconKey()),
          row.labelId() != null,
          row.active(),
          record.updatedAt());
    }
  }

  public record AdminSignTypeList(List<AdminSignTypeItem> items) {}

  /** Body JSON đã dựng sẵn của {@code GET /sign-types} và ETag của nó. */
  public record SignTypesSnapshot(String etag, String json) {
    public static SignTypesSnapshot of(String json) {
      return new SignTypesSnapshot(ETags.of(json), json);
    }

    public byte[] body() {
      return json.getBytes(StandardCharsets.UTF_8);
    }
  }
}
