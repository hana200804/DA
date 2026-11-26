package vn.signa.catalog.internal;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import vn.signa.catalog.internal.CatalogRepository.TypeRow;

class JdbcSignCatalogTest {

  private final LabelImporterTest.FakeRepository repository =
      new LabelImporterTest.FakeRepository();
  private final JdbcSignCatalog catalog = new JdbcSignCatalog(repository);

  @BeforeEach
  void setUp() {
    repository.types.put(
        "P.102",
        new TypeRow("P.102", "PROHIBITORY", "Cấm", null, null, "p.png", 0, 1, "Cấm", true));
    repository.types.put(
        "M.1", new TypeRow("M.1", "OTHER", "Nhập tay", null, null, null, null, null, null, true));
    repository.types.put(
        "OLD", new TypeRow("OLD", "OTHER", "Ngừng", null, null, null, 3, 1, "Ngừng", false));
  }

  @Test
  void findsTypesIncludingInactive() {
    assertThat(catalog.findType("P.102"))
        .hasValueSatisfying(t -> {
          assertThat(t.recognizable()).isTrue();
          assertThat(t.iconUrl()).isEqualTo("/icons/p.png");
        });
    assertThat(catalog.findType("OLD")).hasValueSatisfying(t -> assertThat(t.active()).isFalse());
    assertThat(catalog.findType("NOPE")).isEmpty();
    assertThat(catalog.findType(null)).isEmpty();
    assertThat(catalog.categoryOf("OLD")).contains("OTHER");
    assertThat(catalog.categoryOf("NOPE")).isEmpty();
  }

  @Test
  void recognizableNeedsActiveLabelAndSameVersion() {
    assertThat(catalog.isActiveRecognizable("P.102", 1)).isTrue();
    assertThat(catalog.isActiveRecognizable("P.102", 2)).isFalse();
    assertThat(catalog.isActiveRecognizable("M.1", 1)).isFalse();
    assertThat(catalog.isActiveRecognizable("OLD", 1)).isFalse();
    assertThat(catalog.isActiveRecognizable("NOPE", 1)).isFalse();
  }
}
