package vn.signa.identity.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
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
import org.springframework.test.web.servlet.ResultActions;
import vn.signa.identity.internal.AccountService;
import vn.signa.identity.internal.AccountService.LoginResult;
import vn.signa.identity.internal.AccountService.Profile;
import vn.signa.identity.internal.Client;
import vn.signa.identity.internal.RolePermissions;
import vn.signa.identity.internal.SessionService;
import vn.signa.shared.error.ApiException;
import vn.signa.shared.error.ErrorCode;
import vn.signa.shared.web.RequestIdFilter;

@WebMvcTest(controllers = AuthController.class)
@Import({SecurityConfig.class, SecurityErrorWriter.class, AccessDeniedAdvice.class})
class AuthControllerTest {

  private static final Profile PROFILE =
      new Profile(
          "u-1",
          "a@b.vn",
          "Nguyễn Văn A",
          "USER",
          List.of("report:create", "sign:rate", "submission:create"));

  @Autowired MockMvc mvc;
  @MockitoBean AccountService accounts;
  @MockitoBean SessionService sessions;
  @MockitoBean RolePermissions rolePermissions;

  @BeforeEach
  void permissions() {
    when(rolePermissions.of(anyString())).thenReturn(Set.of());
  }

  private ResultActions postJson(String path, String json) throws Exception {
    return mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content(json));
  }

  @Test
  void registerNormalizesEmailAndReturns201() throws Exception {
    when(accounts.register("a@b.vn", "12345678", "Nguyễn Văn A")).thenReturn(PROFILE);

    postJson(
            "/api/v1/auth/register",
            """
            {"email":"  A@B.VN ","password":"12345678","displayName":" Nguyễn Văn A "}
            """)
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.id").value("u-1"))
        .andExpect(jsonPath("$.email").value("a@b.vn"))
        .andExpect(jsonPath("$.role").value("USER"))
        .andExpect(jsonPath("$.permissions[0]").value("report:create"));
  }

  @Test
  void registerValidatesFields() throws Exception {
    postJson(
            "/api/v1/auth/register",
            """
            {"email":"khong-phai-email","password":"1234567","displayName":"   "}
            """)
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
        .andExpect(jsonPath("$.details[?(@.field == 'email')]").exists())
        .andExpect(jsonPath("$.details[?(@.field == 'password')]").exists())
        .andExpect(jsonPath("$.details[?(@.field == 'displayName')]").exists());
    verify(accounts, never()).register(anyString(), anyString(), anyString());
  }

  @Test
  void registerRejectsPasswordOver72Bytes() throws Exception {
    postJson(
            "/api/v1/auth/register",
            "{\"email\":\"a@b.vn\",\"password\":\"" + "ệ".repeat(30) + "\",\"displayName\":\"A\"}")
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.details[0].field").value("password"))
        .andExpect(jsonPath("$.details[0].issue").value("không được dài quá 72 byte UTF-8"));
  }

  @Test
  void registerRejectsDisplayNameOver100() throws Exception {
    postJson(
            "/api/v1/auth/register",
            "{\"email\":\"a@b.vn\",\"password\":\"12345678\",\"displayName\":\""
                + "a".repeat(101)
                + "\"}")
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.details[0].field").value("displayName"));
  }

  @Test
  void duplicateEmailIs409() throws Exception {
    when(accounts.register(anyString(), anyString(), anyString()))
        .thenThrow(new ApiException(ErrorCode.CONFLICT, "Email đã được đăng ký"));

    postJson(
            "/api/v1/auth/register",
            "{\"email\":\"a@b.vn\",\"password\":\"12345678\",\"displayName\":\"A\"}")
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("CONFLICT"));
  }

  @Test
  void loginReturnsTokenExpiryAndUserWithoutEmail() throws Exception {
    when(accounts.login("a@b.vn", "12345678", Client.MOBILE, "127.0.0.1"))
        .thenReturn(new LoginResult("tok", Instant.parse("2026-12-23T08:00:00.123Z"), PROFILE));

    postJson(
            "/api/v1/auth/login",
            "{\"email\":\"A@b.vn\",\"password\":\"12345678\",\"client\":\"MOBILE\"}")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.token").value("tok"))
        .andExpect(jsonPath("$.expiresAt").value("2026-12-23T08:00:00.123Z"))
        .andExpect(jsonPath("$.user.id").value("u-1"))
        .andExpect(jsonPath("$.user.displayName").value("Nguyễn Văn A"))
        .andExpect(jsonPath("$.user.role").value("USER"))
        .andExpect(jsonPath("$.user.permissions.length()").value(3))
        .andExpect(jsonPath("$.user.email").doesNotExist());
  }

  @Test
  void loginRejectsUnknownClient() throws Exception {
    postJson(
            "/api/v1/auth/login",
            "{\"email\":\"a@b.vn\",\"password\":\"12345678\",\"client\":\"TABLET\"}")
        .andExpect(status().isBadRequest());
    postJson("/api/v1/auth/login", "{\"email\":\"a@b.vn\",\"password\":\"12345678\"}")
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.details[0].field").value("client"));
  }

  @Test
  void loginErrorsKeepCommonFormat() throws Exception {
    when(accounts.login(eq("a@b.vn"), anyString(), any(), anyString()))
        .thenThrow(new ApiException(ErrorCode.UNAUTHENTICATED, "Email hoặc mật khẩu không đúng"));
    postJson("/api/v1/auth/login", "{\"email\":\"a@b.vn\",\"password\":\"sai\",\"client\":\"WEB\"}")
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"))
        .andExpect(jsonPath("$.message").value("Email hoặc mật khẩu không đúng"));

    when(accounts.login(eq("c@d.vn"), anyString(), any(), anyString()))
        .thenThrow(ApiException.rateLimited(600));
    postJson("/api/v1/auth/login", "{\"email\":\"c@d.vn\",\"password\":\"sai\",\"client\":\"WEB\"}")
        .andExpect(status().isTooManyRequests())
        .andExpect(header().string(HttpHeaders.RETRY_AFTER, "600"));
  }

  @Test
  void loginIgnoresStaleTokenOnPublicEndpoint() throws Exception {
    when(sessions.authenticate(anyString())).thenReturn(Optional.empty());
    when(accounts.login(anyString(), anyString(), any(), anyString()))
        .thenReturn(new LoginResult("tok", Instant.parse("2026-12-23T08:00:00Z"), PROFILE));

    mvc.perform(
            post("/api/v1/auth/login")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + "x".repeat(43))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"a@b.vn\",\"password\":\"12345678\",\"client\":\"MOBILE\"}"))
        .andExpect(status().isOk());
  }

  @Test
  void logoutWithoutTokenIs401InCommonFormat() throws Exception {
    mvc.perform(post("/api/v1/auth/logout").header(RequestIdFilter.HEADER, "req-1"))
        .andExpect(status().isUnauthorized())
        .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, "Bearer"))
        .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"))
        .andExpect(jsonPath("$.requestId").value("req-1"));
  }

  @Test
  void logoutRevokesCurrentSession() throws Exception {
    String auth = WebTestSessions.signIn(sessions, "USER");

    mvc.perform(post("/api/v1/auth/logout").header(HttpHeaders.AUTHORIZATION, auth))
        .andExpect(status().isNoContent());
    verify(accounts).logout(WebTestSessions.sessionId("USER"));
  }
}
