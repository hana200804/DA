package vn.signa.identity.web;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import vn.signa.identity.internal.PasswordResetService;
import vn.signa.identity.internal.RolePermissions;
import vn.signa.identity.internal.SessionService;
import vn.signa.shared.error.ApiException;
import vn.signa.shared.error.ErrorCode;

@WebMvcTest(controllers = PasswordResetController.class)
@Import({SecurityConfig.class, SecurityErrorWriter.class, AccessDeniedAdvice.class})
class PasswordResetControllerTest {

  private static final String EMAIL = "a@b.vn";
  private static final String IP = "203.0.113.10";

  @Autowired MockMvc mvc;
  @MockitoBean PasswordResetService passwords;
  @MockitoBean SessionService sessions;
  @MockitoBean RolePermissions rolePermissions;

  @Test
  void forgotIsPublicAndReturnsEmptyAcceptedForAnUnknownAddress() throws Exception {
    mvc.perform(request("forgot", "{\"email\":\"  A@B.VN  \"}"))
        .andExpect(status().isAccepted())
        .andExpect(content().string(""));
    verify(passwords).forgot(EMAIL, IP);
  }

  @Test
  void forgotReturnsRateLimitAndRetryAfter() throws Exception {
    doThrow(ApiException.rateLimited(725)).when(passwords).forgot(EMAIL, IP);

    mvc.perform(request("forgot", "{\"email\":\"a@b.vn\"}"))
        .andExpect(status().isTooManyRequests())
        .andExpect(header().string(HttpHeaders.RETRY_AFTER, "725"))
        .andExpect(jsonPath("$.code").value("RATE_LIMITED"));
  }

  @Test
  void resetIsPublicAndKeepsLeadingZeroesInTheCode() throws Exception {
    mvc.perform(
            request(
                "reset",
                "{\"email\":\"A@B.VN\",\"code\":\"001234\",\"newPassword\":\"mat-khau-moi\"}"))
        .andExpect(status().isNoContent())
        .andExpect(content().string(""));
    verify(passwords).reset(EMAIL, "001234", "mat-khau-moi");
  }

  @Test
  void resetReturnsGenericBusinessRuleError() throws Exception {
    String message = "Mã đặt lại mật khẩu không hợp lệ hoặc đã hết hạn";
    doThrow(new ApiException(ErrorCode.BUSINESS_RULE, message))
        .when(passwords)
        .reset(EMAIL, "sai", "mat-khau-moi");

    mvc.perform(
            request(
                "reset",
                "{\"email\":\"a@b.vn\",\"code\":\"sai\",\"newPassword\":\"mat-khau-moi\"}"))
        .andExpect(status().isUnprocessableContent())
        .andExpect(jsonPath("$.code").value("BUSINESS_RULE"))
        .andExpect(jsonPath("$.message").value(message));
  }

  @Test
  void resetAppliesRegistrationPasswordPolicy() throws Exception {
    mvc.perform(
            request(
                "reset",
                "{\"email\":\"a@b.vn\",\"code\":\"001234\",\"newPassword\":\""
                    + "é".repeat(37)
                    + "\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.details[0].field").value("newPassword"));
    verifyNoInteractions(passwords);
  }

  private static MockHttpServletRequestBuilder request(String route, String body) {
    return post("/api/v1/auth/password/" + route)
        .with(request -> {
          request.setRemoteAddr(IP);
          return request;
        })
        .contentType(MediaType.APPLICATION_JSON)
        .content(body);
  }
}
