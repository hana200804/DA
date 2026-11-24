package vn.signa.recognition.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import vn.signa.catalog.api.SignCatalog;
import vn.signa.catalog.api.SignTypeView;

class CatalogSignInfoLookupTest {

  private final SignCatalog catalog = mock(SignCatalog.class);
  private final CatalogSignInfoLookup lookup = new CatalogSignInfoLookup(catalog);

  @Test
  void returnsNameDescriptionAndIconUrlFromCatalog() {
    when(catalog.findType("P.123a"))
        .thenReturn(
            Optional.of(
                new SignTypeView(
                    "P.123a",
                    "PROHIBITORY",
                    "Cấm rẽ trái",
                    "Cấm các loại xe rẽ trái",
                    null,
                    "P.123a.png",
                    7,
                    1,
                    true)));

    var result = lookup.lookup(List.of("P.123a"));

    assertThat(result)
        .containsEntry(
            "P.123a",
            new SignInfoLookup.SignInfo(
                "Cấm rẽ trái", "Cấm các loại xe rẽ trái", "/icons/P.123a.png"));
  }

  @Test
  void typeWithoutIconOrDescriptionHasNulls() {
    when(catalog.findType("W.201a"))
        .thenReturn(
            Optional.of(
                new SignTypeView(
                    "W.201a", "WARNING", "Chỗ ngoặt nguy hiểm", null, null, null, 3, 1, true)));

    assertThat(lookup.lookup(List.of("W.201a")))
        .containsEntry("W.201a", new SignInfoLookup.SignInfo("Chỗ ngoặt nguy hiểm", null, null));
  }

  @Test
  void unknownCodeIsLeftOut() {
    when(catalog.findType(anyString())).thenReturn(Optional.empty());

    assertThat(lookup.lookup(List.of("X.999"))).isEmpty();
  }

  @Test
  void eachDistinctCodeIsLookedUpOnce() {
    when(catalog.findType(anyString())).thenReturn(Optional.empty());

    lookup.lookup(List.of("P.102", "P.102", "W.201a", "P.102"));

    verify(catalog, times(1)).findType("P.102");
    verify(catalog, times(1)).findType("W.201a");
  }

  @Test
  void catalogOutageDegradesToNoInfo() {
    when(catalog.findType(anyString())).thenThrow(new DataAccessResourceFailureException("down"));

    assertThat(lookup.lookup(List.of("P.102"))).isEmpty();
  }
}
