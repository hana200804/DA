package vn.signa.identity.web;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import vn.signa.identity.internal.AccountService;
import vn.signa.identity.internal.AccountService.Profile;
import vn.signa.identity.internal.RolePermissions;
import vn.signa.identity.internal.SessionService;
import vn.signa.shared.error.ApiException;
import vn.signa.shared.error.ErrorCode;
import vn.signa.shared.web.RequestIdFilter;

@WebMvcTest(controllers = MeController.class)
@Import({SecurityConfig.class, SecurityErrorWriter.class, AccessDeniedAdvice.class})
class MeControllerTest {

  private static final String USER_ID = WebTestSessions.userId("MODERATOR");
  private static final Profile PROFILE =
      new Profile(USER_ID, "m@b.vn", "Kiểm duyệt", "MODERATOR", List.of("review:decide"));

  @Autowired MockMvc mvc;
  @MockitoBean AccountService accounts;
  @MockitoBean SessionService sessions;
  @MockitoBean RolePermissions rolePermissions;

  private String auth;

  @BeforeEach
  void signIn() {
    when(rolePermissions.of("MODERATOR")).thenReturn(Set.of("review:decide"));
    auth = WebTestSessions.signIn(sessions, "MODERATOR");
  }

  @Test
  void meReturnsUnwrappedProfileWithEmail() throws Exception {
    when(accounts.profile(USER_ID)).thenReturn(PROFILE);

    mvc.perform(get("/api/v1/me").header(HttpHeaders.AUTHORIZATION, auth))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value(USER_ID))
        .andExpect(jsonPath("$.email").value("m@b.vn"))
        .andExpect(jsonPath("$.displayName").value("Kiểm duyệt"))
        .andExpect(jsonPath("$.role").value("MODERATOR"))
        .andExpect(jsonPath("$.permissions[0]").value("review:decide"))
        .andExpect(jsonPath("$.user").doesNotExist());
  }

  @Test
  void missingWrongOrRevokedTokenIs401() throws Exception {
    when(sessions.authenticate(anyString())).thenReturn(Optional.empty());
    String revoked = "Bearer " + "r".repeat(43);

    for (String header : new String[] {null, "Bearer sai", revoked, "Basic dTpw"}) {
      var request = get("/api/v1/me").header(RequestIdFilter.HEADER, "req-2");
      if (header != null) {
        request.header(HttpHeaders.AUTHORIZATION, header);
      }
      mvc.perform(request)
          .andExpect(status().isUnauthorized())
          .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"))
          .andExpect(jsonPath("$.message").value("Cần đăng nhập"))
          .andExpect(jsonPath("$.requestId").value("req-2"));
    }
    verify(accounts, never()).profile(anyString());
  }

  @Test
  void sessionStoreFailureIs500InCommonFormat() throws Exception {
    String token = "z".repeat(43);
    when(sessions.authenticate(token)).thenThrow(new QueryTimeoutException("redis"));

    mvc.perform(get("/api/v1/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
        .andExpect(status().isInternalServerError())
        .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
        .andExpect(header().exists(RequestIdFilter.HEADER));
  }

  @Test
  void patchRenames() throws Exception {
    when(accounts.rename(USER_ID, "Tên mới")).thenReturn(PROFILE);

    mvc.perform(
            patch("/api/v1/me")
                .header(HttpHeaders.AUTHORIZATION, auth)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"displayName\":\"  Tên mới \"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.email").value("m@b.vn"));
  }

  @Test
  void patchRejectsBlankName() throws Exception {
    mvc.perform(
            patch("/api/v1/me")
                .header(HttpHeaders.AUTHORIZATION, auth)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"displayName\":\"  \"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.details[0].field").value("displayName"));
  }

  @Test
  void changePasswordKeepsCurrentSession() throws Exception {
    mvc.perform(
            put("/api/v1/me/password")
                .header(HttpHeaders.AUTHORIZATION, auth)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"currentPassword\":\"cu-12345\",\"newPassword\":\"moi-12345\"}"))
        .andExpect(status().isNoContent());
    verify(accounts)
        .changePassword(USER_ID, WebTestSessions.sessionId("MODERATOR"), "cu-12345", "moi-12345");
  }

  @Test
  void wrongCurrentPasswordIs422() throws Exception {
    doThrow(new ApiException(ErrorCode.BUSINESS_RULE, "Mật khẩu hiện tại không đúng"))
        .when(accounts)
        .changePassword(USER_ID, WebTestSessions.sessionId("MODERATOR"), "sai", "moi-12345");

    mvc.perform(
            put("/api/v1/me/password")
                .header(HttpHeaders.AUTHORIZATION, auth)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"currentPassword\":\"sai\",\"newPassword\":\"moi-12345\"}"))
        .andExpect(status().isUnprocessableContent())
        .andExpect(jsonPath("$.code").value("BUSINESS_RULE"));
  }

  @Test
  void newPasswordMustFollowPolicy() throws Exception {
    mvc.perform(
            put("/api/v1/me/password")
                .header(HttpHeaders.AUTHORIZATION, auth)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"currentPassword\":\"cu-12345\",\"newPassword\":\"ngan\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.details[0].field").value("newPassword"));
  }
}
