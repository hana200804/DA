package vn.signa.catalog.internal;

import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import vn.signa.catalog.api.SignCatalog;
import vn.signa.catalog.api.SignTypeView;
import vn.signa.catalog.internal.CatalogRepository.CategoryRow;
import vn.signa.catalog.internal.CatalogRepository.TypeRow;

@Service
class JdbcSignCatalog implements SignCatalog {

  private final CatalogRepository repository;

  JdbcSignCatalog(CatalogRepository repository) {
    this.repository = repository;
  }

  @Override
  public Optional<SignTypeView> findType(String code) {
    if (code == null) return Optional.empty();
    return repository.type(code).map(JdbcSignCatalog::view);
  }

  @Override
  @Transactional(propagation = Propagation.MANDATORY)
  public Optional<SignTypeView> findTypeForSignWrite(String code) {
    return repository.shareType(code).map(JdbcSignCatalog::view);
  }

  @Override
  public Optional<String> categoryOf(String code) {
    return findType(code).map(SignTypeView::categoryCode);
  }

  @Override
  public Map<String, String> categoryNames() {
    return repository.categories().stream()
        .collect(Collectors.toUnmodifiableMap(CategoryRow::code, CategoryRow::name));
  }

  @Override
  public boolean isActiveRecognizable(String code, int labelMapVersion) {
    return findType(code)
        .filter(SignTypeView::active)
        .filter(SignTypeView::recognizable)
        .filter(t -> Objects.equals(t.labelMapVersion(), labelMapVersion))
        .isPresent();
  }

  @Override
  public Set<Integer> supportedLabelMapVersions() {
    return Collections.unmodifiableSet(repository.recognizableVersions());
  }

  static SignTypeView view(TypeRow row) {
    return new SignTypeView(
        row.code(),
        row.categoryCode(),
        row.name(),
        row.description(),
        row.speedKmh(),
        row.iconKey(),
        row.labelId(),
        row.labelMapVersion(),
        row.active());
  }
}
