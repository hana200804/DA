package vn.signa.identity.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import vn.signa.identity.internal.ManagedUser;
import vn.signa.identity.internal.RolePermissions;
import vn.signa.identity.internal.SessionService;
import vn.signa.identity.internal.UserAdminService;
import vn.signa.identity.internal.UserPage;
import vn.signa.identity.internal.UserSearch;
import vn.signa.identity.internal.UserStatus;
import vn.signa.shared.error.ApiException;
import vn.signa.shared.error.ErrorCode;

@WebMvcTest(controllers = AdminUserController.class)
@Import({SecurityConfig.class, SecurityErrorWriter.class, AccessDeniedAdvice.class})
class AdminUserControllerTest {

  private static final String URL = "/api/v1/admin/users";
  private static final String ADMIN_ID = WebTestSessions.userId("ADMIN");

  @Autowired MockMvc mvc;
  @MockitoBean UserAdminService service;
  @MockitoBean SessionService sessions;
  @MockitoBean RolePermissions rolePermissions;

  private String admin;
  private String moderator;

  @BeforeEach
  void signIn() {
    when(rolePermissions.of("ADMIN")).thenReturn(Set.of("user:manage", "audit:read"));
    when(rolePermissions.of("MODERATOR")).thenReturn(Set.of("audit:read", "review:decide"));
    admin = WebTestSessions.signIn(sessions, "ADMIN");
    moderator = WebTestSessions.signIn(sessions, "MODERATOR");
    when(service.search(any())).thenReturn(new UserPage(List.of(), 0, 20, 0));
  }

  private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder patchUser(
      String id, String body) {
    return patch(URL + "/" + id)
        .header(HttpHeaders.AUTHORIZATION, admin)
        .contentType(MediaType.APPLICATION_JSON)
        .content(body);
  }

  @Test
  void listReturnsPageWithoutPasswordHash() throws Exception {
    ManagedUser row =
        new ManagedUser(
            "u-2",
            "b@signa.vn",
            "Người B",
            "MODERATOR",
            UserStatus.ACTIVE,
            Instant.parse("2026-11-01T01:02:03.004Z"),
            null);
    when(service.search(new UserSearch("b@", "MODERATOR", UserStatus.ACTIVE, 1, 10)))
        .thenReturn(new UserPage(List.of(row), 1, 10, 11));

    mvc.perform(
            get(URL)
                .header(HttpHeaders.AUTHORIZATION, admin)
                .param("q", "b@")
                .param("role", "MODERATOR")
                .param("status", "ACTIVE")
                .param("page", "1")
                .param("size", "10"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.total").value(11))
        .andExpect(jsonPath("$.page").value(1))
        .andExpect(jsonPath("$.size").value(10))
        .andExpect(jsonPath("$.items[0].id").value("u-2"))
        .andExpect(jsonPath("$.items[0].email").value("b@signa.vn"))
        .andExpect(jsonPath("$.items[0].displayName").value("Người B"))
        .andExpect(jsonPath("$.items[0].role").value("MODERATOR"))
        .andExpect(jsonPath("$.items[0].status").value("ACTIVE"))
        .andExpect(jsonPath("$.items[0].createdAt").value("2026-11-01T01:02:03.004Z"))
        .andExpect(jsonPath("$.items[0].lastLoginAt").isEmpty())
        .andExpect(jsonPath("$.items[0].passwordHash").doesNotExist());
  }

  @Test
  void listRejectsBadParameters() throws Exception {
    String[][] bad = {
      {"role", "ROOT"},
      {"status", "BANNED"},
      {"size", "101"},
      {"page", "-1"},
      {"q", "x".repeat(256)}
    };
    for (String[] param : bad) {
      mvc.perform(get(URL).header(HttpHeaders.AUTHORIZATION, admin).param(param[0], param[1]))
          .andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }
  }

  @Test
  void moderatorIs403() throws Exception {
    mvc.perform(get(URL).header(HttpHeaders.AUTHORIZATION, moderator))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    mvc.perform(
            patch(URL + "/u-2")
                .header(HttpHeaders.AUTHORIZATION, moderator)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":\"LOCKED\"}"))
        .andExpect(status().isForbidden());
    verify(service, never()).search(any());
    verify(service, never()).update(anyString(), anyString(), any(), any());
  }

  @Test
  void patchPassesActorAndReturns204() throws Exception {
    mvc.perform(patchUser("u-2", "{\"role\":\"MODERATOR\",\"status\":\"LOCKED\"}"))
        .andExpect(status().isNoContent());
    verify(service).update(ADMIN_ID, "u-2", "MODERATOR", UserStatus.LOCKED);

    mvc.perform(patchUser("u-3", "{\"status\":\"ACTIVE\"}")).andExpect(status().isNoContent());
    verify(service).update(ADMIN_ID, "u-3", null, UserStatus.ACTIVE);
  }

  @Test
  void patchNeedsAtLeastOneValidField() throws Exception {
    for (String body :
        new String[] {
          "{}", "{\"displayName\":\"X\"}", "{\"role\":\"ROOT\"}", "{\"status\":\"X\"}"
        }) {
      mvc.perform(patchUser("u-2", body))
          .andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }
    verify(service, never()).update(anyString(), anyString(), any(), any());
  }

  @Test
  void businessErrorsKeepTheirStatus() throws Exception {
    doThrow(new ApiException(ErrorCode.BUSINESS_RULE, "Không thể tự hạ vai trò"))
        .when(service)
        .update(eq(ADMIN_ID), eq(ADMIN_ID), any(), any());
    doThrow(ApiException.notFound())
        .when(service)
        .update(eq(ADMIN_ID), eq("khong-co"), any(), any());

    mvc.perform(patchUser(ADMIN_ID, "{\"status\":\"LOCKED\"}"))
        .andExpect(status().isUnprocessableContent())
        .andExpect(jsonPath("$.code").value("BUSINESS_RULE"));
    mvc.perform(patchUser("khong-co", "{\"status\":\"LOCKED\"}"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("NOT_FOUND"));
  }
}
