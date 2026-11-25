package vn.signa.catalog.internal;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;
import vn.signa.catalog.internal.CatalogRepository.TypeRow;
import vn.signa.catalog.internal.CatalogResponses.SignCategoryItem;
import vn.signa.catalog.internal.CatalogResponses.SignCategoryList;
import vn.signa.catalog.internal.CatalogResponses.SignTypeItem;
import vn.signa.catalog.internal.CatalogResponses.SignTypeList;
import vn.signa.catalog.internal.CatalogResponses.SignTypesSnapshot;

/** Đọc danh mục cho API công khai (FR-08). Chỉ loại {@code active}. */
@Service
public class CatalogQueryService {

  private final CatalogRepository repository;
  private final SignTypesCache cache;
  private final JsonMapper json;

  CatalogQueryService(CatalogRepository repository, SignTypesCache cache, JsonMapper json) {
    this.repository = repository;
    this.cache = cache;
    this.json = json;
  }

  public SignCategoryList categories() {
    return new SignCategoryList(
        repository.categories().stream().map(SignCategoryItem::of).toList());
  }

  /** Toàn bộ loại đang dùng, lấy từ {@code cache:sign-types} nếu có. */
  public SignTypesSnapshot signTypes() {
    long generation = repository.generation();
    Optional<String> cached = cache.get(generation);
    if (cached.isPresent()) return SignTypesSnapshot.of(cached.get());
    String body = json.writeValueAsString(buildSignTypes(repository.activeTypes()));
    cache.put(generation, body);
    return SignTypesSnapshot.of(body);
  }

  public Optional<SignTypeItem> activeType(String code) {
    return repository.type(code).filter(TypeRow::active).map(SignTypeItem::of);
  }

  static SignTypeList buildSignTypes(List<TypeRow> active) {
    Integer version =
        active.stream()
            .filter(t -> t.labelId() != null)
            .map(TypeRow::labelMapVersion)
            .filter(Objects::nonNull)
            .max(Comparator.naturalOrder())
            .orElse(null);
    return new SignTypeList(version, active.stream().map(SignTypeItem::of).toList());
  }
}
