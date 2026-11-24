package vn.signa.catalog.web;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import vn.signa.catalog.internal.CatalogQueryService;
import vn.signa.catalog.internal.CatalogResponses.SignCategoryItem;
import vn.signa.catalog.internal.CatalogResponses.SignCategoryList;
import vn.signa.catalog.internal.CatalogResponses.SignTypeItem;
import vn.signa.catalog.internal.CatalogResponses.SignTypesSnapshot;
import vn.signa.identity.web.WithApiSecurity;
import vn.signa.shared.web.GlobalExceptionHandler;
import vn.signa.shared.web.RequestIdFilter;

@WebMvcTest(controllers = CatalogController.class)
@WithApiSecurity
@Import({GlobalExceptionHandler.class, RequestIdFilter.class})
class CatalogControllerTest {

  private static final SignTypesSnapshot SNAPSHOT =
      SignTypesSnapshot.of(
          "{\"labelMapVersion\":1,\"items\":[{\"code\":\"P.102\",\"name\":\"Cấm đi ngược chiều\"}]}");

  @Autowired MockMvc mvc;
  @MockitoBean CatalogQueryService catalog;

  @Test
  void listsCategories() throws Exception {
    when(catalog.categories())
        .thenReturn(
            new SignCategoryList(
                List.of(new SignCategoryItem("PROHIBITORY", "Biển cấm", null, 1))));

    mvc.perform(get("/api/v1/sign-categories"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items[0].code").value("PROHIBITORY"))
        .andExpect(jsonPath("$.items[0].name").value("Biển cấm"))
        .andExpect(jsonPath("$.items[0].sortOrder").value(1))
        .andExpect(jsonPath("$.items[0].description").isEmpty());
  }

  @Test
  void signTypesCarryETag() throws Exception {
    when(catalog.signTypes()).thenReturn(SNAPSHOT);

    mvc.perform(get("/api/v1/sign-types"))
        .andExpect(status().isOk())
        .andExpect(header().string("ETag", SNAPSHOT.etag()))
        .andExpect(header().string("Cache-Control", "no-cache"))
        .andExpect(content().contentTypeCompatibleWith("application/json"))
        .andExpect(jsonPath("$.labelMapVersion").value(1))
        .andExpect(jsonPath("$.items[0].name").value("Cấm đi ngược chiều"));
  }

  @Test
  void matchingIfNoneMatchGives304WithoutBody() throws Exception {
    when(catalog.signTypes()).thenReturn(SNAPSHOT);

    mvc.perform(get("/api/v1/sign-types").header("If-None-Match", SNAPSHOT.etag()))
        .andExpect(status().isNotModified())
        .andExpect(header().string("ETag", SNAPSHOT.etag()))
        .andExpect(content().string(""));
    mvc.perform(
            get("/api/v1/sign-types").header("If-None-Match", "\"other\", W/" + SNAPSHOT.etag()))
        .andExpect(status().isNotModified());
  }

  @Test
  void staleIfNoneMatchGivesFullBody() throws Exception {
    when(catalog.signTypes()).thenReturn(SNAPSHOT);

    mvc.perform(get("/api/v1/sign-types").header("If-None-Match", "\"stale\""))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items[0].code").value("P.102"));
  }

  @Test
  void signTypeDetail() throws Exception {
    when(catalog.activeType("P.127_50"))
        .thenReturn(
            Optional.of(
                new SignTypeItem(
                    "P.127_50",
                    "PROHIBITORY",
                    "Tốc độ tối đa cho phép 50 km/h",
                    null,
                    50,
                    "/icons/P.127_50.png",
                    true)));

    mvc.perform(get("/api/v1/sign-types/P.127_50"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value("P.127_50"))
        .andExpect(jsonPath("$.speedKmh").value(50))
        .andExpect(jsonPath("$.iconUrl").value("/icons/P.127_50.png"))
        .andExpect(jsonPath("$.recognizable").value(true));
  }

  @Test
  void unknownOrInactiveTypeIs404() throws Exception {
    when(catalog.activeType("OLD")).thenReturn(Optional.empty());

    mvc.perform(get("/api/v1/sign-types/OLD"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("NOT_FOUND"));
  }
}
