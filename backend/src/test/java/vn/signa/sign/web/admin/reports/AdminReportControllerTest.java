package vn.signa.sign.web.admin.reports;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import vn.signa.identity.api.AuthenticatedUser;
import vn.signa.identity.web.WithApiSecurity;
import vn.signa.shared.error.ApiException;
import vn.signa.shared.error.ErrorCode;
import vn.signa.sign.internal.feedback.ReportAcceptance;
import vn.signa.sign.internal.feedback.ReportAcceptance.Action;
import vn.signa.sign.internal.feedback.ReportAcceptance.Changes;
import vn.signa.sign.internal.feedback.ReportService;
import vn.signa.sign.internal.review.ReviewRequests.LocationInput;

@WebMvcTest(AdminReportController.class)
@WithApiSecurity
class AdminReportControllerTest {

  private static final String ACCEPT = "/api/v1/admin/reports/r-1/accept";
  private static final String ACTOR = "moderator-1";
  private static final String RESOLVE = "report:resolve";
  private static final String REMOVE = "{\"action\":\"REMOVE\",\"version\":7}";

  @Autowired MockMvc mvc;
  @MockitoBean ReportService reports;

  @Test
  void editPassesTheVersionActorAndChanges() throws Exception {
    mvc.perform(
            accept(
                    """
                    {"action":"EDIT","version":7,"changes":{"signTypeCode":" P.102 ",
                     "location":{"lat":21.0285,"lng":105.8048}}}
                    """)
                .with(as(RESOLVE)))
        .andExpect(status().isNoContent());

    verify(reports)
        .accept(
            "r-1",
            ACTOR,
            new ReportAcceptance(
                Action.EDIT, 7, new Changes("P.102", new LocationInput(21.0285, 105.8048))));
  }

  @Test
  void removeOnlyRequiresReportResolvePermission() throws Exception {
    mvc.perform(accept(REMOVE)).andExpect(status().isUnauthorized());
    mvc.perform(accept(REMOVE).with(as("sign:edit"))).andExpect(status().isForbidden());
    verifyNoInteractions(reports);

    mvc.perform(accept(REMOVE).with(as(RESOLVE))).andExpect(status().isNoContent());
    verify(reports).accept("r-1", ACTOR, new ReportAcceptance(Action.REMOVE, 7, null));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "{\"action\":\"EDIT\",\"version\":7}",
        "{\"action\":\"EDIT\",\"version\":7,\"changes\":{}}",
        "{\"action\":\"EDIT\",\"version\":7,\"changes\":{\"signTypeCode\":\" \"}}",
        "{\"action\":\"EDIT\",\"version\":7,\"changes\":{\"location\":{\"lat\":91,\"lng\":105}}}",
        "{\"action\":\"REMOVE\",\"version\":7,\"changes\":{\"signTypeCode\":\"P.102\"}}",
        "{\"action\":\"REMOVE\"}",
        "{\"action\":\"REMOVE\",\"version\":-1}",
        "{\"action\":\"DISMISS\",\"version\":7}",
        "{\"action\":0,\"version\":7,\"changes\":{\"signTypeCode\":\"P.102\"}}"
      })
  void rejectsInvalidAcceptance(String body) throws Exception {
    mvc.perform(accept(body).with(as(RESOLVE)))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    verifyNoInteractions(reports);
  }

  @ParameterizedTest
  @EnumSource(
      value = ErrorCode.class,
      names = {"SIGN_ALREADY_PROCESSED", "CONFLICT"})
  void keepsTheServiceConflictCode(ErrorCode code) throws Exception {
    doThrow(new ApiException(code)).when(reports).accept(eq("r-1"), eq(ACTOR), any());

    mvc.perform(accept(REMOVE).with(as(RESOLVE)))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value(code.name()));
  }

  private static MockHttpServletRequestBuilder accept(String body) {
    return post(ACCEPT).contentType(MediaType.APPLICATION_JSON).content(body);
  }

  private static RequestPostProcessor as(String permission) {
    var user = new AuthenticatedUser(ACTOR, "MODERATOR", Set.of(permission));
    return authentication(
        UsernamePasswordAuthenticationToken.authenticated(
            user, null, List.of(new SimpleGrantedAuthority(permission))));
  }
}
