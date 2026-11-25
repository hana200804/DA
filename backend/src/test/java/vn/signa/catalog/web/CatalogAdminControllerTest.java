package vn.signa.catalog.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import vn.signa.catalog.internal.CatalogAdminRequests.EditType;
import vn.signa.catalog.internal.CatalogAdminRequests.NewCategory;
import vn.signa.catalog.internal.CatalogAdminRequests.NewType;
import vn.signa.catalog.internal.CatalogAdminService;
import vn.signa.catalog.internal.CatalogResponses.AdminSignTypeItem;
import vn.signa.catalog.internal.CatalogResponses.AdminSignTypeList;
import vn.signa.catalog.internal.CatalogResponses.SignCategoryItem;
import vn.signa.identity.api.AuthenticatedUser;
import vn.signa.identity.web.WithApiSecurity;
import vn.signa.shared.error.ApiException;
import vn.signa.shared.error.ErrorCode;

@WebMvcTest(CatalogAdminController.class)
@WithApiSecurity
class CatalogAdminControllerTest {

  private static final String TYPES = "/api/v1/admin/sign-types";
  private static final String CATEGORIES = "/api/v1/admin/sign-categories";
  private static final String ACTOR = "admin-1";
  private static final AdminSignTypeItem TYPE =
      new AdminSignTypeItem(
          "T.1",
          "OTHER",
          "Biển thử",
          null,
          null,
          null,
          false,
          false,
          Instant.parse("2026-11-24T00:00:00Z"));

  @Autowired MockMvc mvc;
  @MockitoBean CatalogAdminService catalog;

  @Test
  void listsInactiveTypesWithAdminFields() throws Exception {
    when(catalog.types(false)).thenReturn(new AdminSignTypeList(List.of(TYPE)));

    mvc.perform(get(TYPES).param("active", "false").with(admin()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items[0].code").value("T.1"))
        .andExpect(jsonPath("$.items[0].active").value(false))
        .andExpect(jsonPath("$.items[0].updatedAt").value("2026-11-24T00:00:00Z"));
    verify(catalog).types(false);
  }

  @Test
  void normalizesCategoryCodeAndNameOnCreate() throws Exception {
    when(catalog.createCategory(eq(ACTOR), any()))
        .thenReturn(new SignCategoryItem("OTHER_2", "Mới", null, 5));

    mvc.perform(
            post(CATEGORIES)
                .with(admin())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"code\":\" other_2 \",\"name\":\" Mới \",\"sortOrder\":5}"))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.code").value("OTHER_2"));
    verify(catalog).createCategory(ACTOR, new NewCategory("OTHER_2", "Mới", null, 5));
  }

  @Test
  void rejectsInvalidCategoryCodeAndOverlongTypeCode() throws Exception {
    mvc.perform(
            post(CATEGORIES)
                .with(admin())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"code\":\"2OTHER\",\"name\":\"Mới\",\"sortOrder\":5}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.details[0].field").value("code"));
    mvc.perform(
            post(TYPES)
                .with(admin())
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"code\":\"%s\",\"name\":\"Mới\",\"categoryCode\":\"OTHER\"}"
                        .formatted("X".repeat(31))))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.details[0].field").value("code"));
    for (String invalid : new String[] {"TEST/A", "TEST,A", ".."}) {
      mvc.perform(
              post(TYPES)
                  .with(admin())
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(
                      "{\"code\":\"%s\",\"name\":\"Mới\",\"categoryCode\":\"OTHER\"}"
                          .formatted(invalid)))
          .andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.details[0].field").value("code"));
    }
    verifyNoInteractions(catalog);
  }

  @Test
  void duplicateTypeReturnsConflict() throws Exception {
    when(catalog.createType(eq(ACTOR), any(NewType.class)))
        .thenThrow(new ApiException(ErrorCode.CONFLICT));

    mvc.perform(
            post(TYPES)
                .with(admin())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"code\":\"T.1\",\"categoryCode\":\"OTHER\",\"name\":\"Biển thử\"}"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("CONFLICT"));
  }

  @Test
  void patchPreservesDifferenceBetweenNullAndOmitted() throws Exception {
    when(catalog.editType(eq(ACTOR), eq("T.1"), any())).thenReturn(TYPE);

    mvc.perform(
            patch(TYPES + "/T.1")
                .with(admin())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"speedKmh\":null,\"active\":false}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.active").value(false));

    ArgumentCaptor<EditType> request = ArgumentCaptor.forClass(EditType.class);
    verify(catalog).editType(eq(ACTOR), eq("T.1"), request.capture());
    assertThat(request.getValue().speedKmh().isNull()).isTrue();
    assertThat(request.getValue().description()).isNull();
  }

  @Test
  void uploadRequiresIconPartAndRejectsMoreThanOneMegabyte() throws Exception {
    mvc.perform(multipart(HttpMethod.PUT, TYPES + "/T.1/icon").with(admin()))
        .andExpect(status().isBadRequest());
    mvc.perform(
            multipart(HttpMethod.PUT, TYPES + "/T.1/icon")
                .file(
                    new MockMultipartFile(
                        "icon", "test.png", "image/png", new byte[1024 * 1024 + 1]))
                .with(admin()))
        .andExpect(status().is(413))
        .andExpect(jsonPath("$.code").value("PAYLOAD_TOO_LARGE"));
    verifyNoInteractions(catalog);
  }

  /**
   * Quy tắc URL {@code /admin/sign-types/**}, {@code /admin/sign-categories/**} đặt trước mặc định
   * {@code /admin/**} cần {@code review:decide}: chỉ {@code catalog:manage} là đủ, chỉ {@code
   * review:decide} thì không.
   */
  @Test
  void catalogManageAloneIsEnoughAndReviewDecideAloneIsNot() throws Exception {
    when(catalog.types(true)).thenReturn(new AdminSignTypeList(List.of()));
    mvc.perform(get(TYPES).param("active", "true").with(admin())).andExpect(status().isOk());

    var reviewer = new AuthenticatedUser("mod-1", "MODERATOR", Set.of("review:decide"));
    RequestPostProcessor moderator =
        authentication(
            UsernamePasswordAuthenticationToken.authenticated(
                reviewer, null, List.of(new SimpleGrantedAuthority("review:decide"))));
    mvc.perform(get(TYPES).with(moderator)).andExpect(status().isForbidden());
    mvc.perform(
            post(CATEGORIES)
                .with(moderator)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{khong-phai-json"))
        .andExpect(status().isForbidden());
  }

  private static RequestPostProcessor admin() {
    String permission = "catalog:manage";
    var user = new AuthenticatedUser(ACTOR, "ADMIN", Set.of(permission));
    return authentication(
        UsernamePasswordAuthenticationToken.authenticated(
            user, null, List.of(new SimpleGrantedAuthority(permission))));
  }
}
