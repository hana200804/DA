package vn.signa.sign.web.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static vn.signa.sign.web.admin.AdminRequests.ACTOR;
import static vn.signa.sign.web.admin.AdminRequests.as;
import static vn.signa.sign.web.admin.AdminRequests.moderator;

import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import vn.signa.identity.web.WithApiSecurity;
import vn.signa.shared.error.ApiException;
import vn.signa.shared.error.ErrorCode;
import vn.signa.sign.internal.review.ReviewDetails;
import vn.signa.sign.internal.review.ReviewQueryService;
import vn.signa.sign.internal.review.ReviewRequests.Approve;
import vn.signa.sign.internal.review.ReviewRequests.Edit;
import vn.signa.sign.internal.review.ReviewRequests.Keep;
import vn.signa.sign.internal.review.ReviewRequests.LocationInput;
import vn.signa.sign.internal.review.ReviewRequests.NewSign;
import vn.signa.sign.internal.review.ReviewRequests.Reasoned;
import vn.signa.sign.internal.review.ReviewService;
import vn.signa.sign.internal.review.ReviewViews.Created;

@WebMvcTest(AdminSignController.class)
@WithApiSecurity
class AdminSignControllerTest {

  private static final String SIGNS = "/api/v1/admin/signs";

  @Autowired MockMvc mvc;
  @MockitoBean ReviewQueryService queries;
  @MockitoBean ReviewService reviews;

  @Test
  void detailHasDocumentedFieldsAndIsNotCached() throws Exception {
    when(queries.detail("c1a9")).thenReturn(Optional.of(ReviewDetails.sample("c1a9")));

    mvc.perform(get(SIGNS + "/c1a9").with(as("review:decide")))
        .andExpect(status().isOk())
        .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
        .andExpect(jsonPath("$.id").value("c1a9"))
        .andExpect(jsonPath("$.signName").value("Cấm rẽ trái"))
        .andExpect(jsonPath("$.version").value(7))
        .andExpect(jsonPath("$.locationLocked").value(false))
        .andExpect(jsonPath("$.relatedSignId").doesNotExist())
        .andExpect(jsonPath("$.coverCropUrl").value("https://minio/cover.jpg"))
        .andExpect(jsonPath("$.confirmCount").value(0))
        .andExpect(jsonPath("$.denyCount").value(0))
        .andExpect(jsonPath("$.codeWeights['P.123a']").value(1.8))
        .andExpect(jsonPath("$.submissions[0].cropUrl").value("https://minio/sub-1.jpg"))
        .andExpect(jsonPath("$.submissions[0].receivedAt").value("2026-01-15T08:30:13.002Z"))
        .andExpect(jsonPath("$.submissions[0].location.lng").value(105.80479))
        .andExpect(jsonPath("$.nearbySigns[0].id").value("near-1"))
        .andExpect(jsonPath("$.nearbySigns[0].status").value("APPROVED"));
  }

  @Test
  void missingSignIsNotFound() throws Exception {
    when(queries.detail("nope")).thenReturn(Optional.empty());

    mvc.perform(get(SIGNS + "/nope").with(moderator()))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("NOT_FOUND"));
  }

  @Test
  void approvePassesActorAndBody() throws Exception {
    mvc.perform(
            json(
                post(SIGNS + "/s-1/approve"),
                """
                {"version":7,"signTypeCode":" P.123b ","location":{"lat":21.02851,"lng":105.80473}}
                """))
        .andExpect(status().isNoContent());

    verify(reviews)
        .approve(ACTOR, "s-1", new Approve(7, "P.123b", new LocationInput(21.02851, 105.80473)));
  }

  @Test
  void approveWithoutVersionIsValidationError() throws Exception {
    mvc.perform(json(post(SIGNS + "/s-1/approve"), "{\"location\":{\"lat\":91,\"lng\":0}}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    mvc.perform(json(post(SIGNS + "/s-1/approve"), "{\"version\":"))
        .andExpect(status().isBadRequest());
    verifyNoInteractions(reviews);
  }

  @Test
  void conflictIsSignAlreadyProcessed() throws Exception {
    doThrow(new ApiException(ErrorCode.SIGN_ALREADY_PROCESSED))
        .when(reviews)
        .approve(eq(ACTOR), eq("s-1"), any());

    mvc.perform(json(post(SIGNS + "/s-1/approve"), "{\"version\":6}"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("SIGN_ALREADY_PROCESSED"));
  }

  @Test
  void rejectNeedsReason() throws Exception {
    mvc.perform(json(post(SIGNS + "/s-1/reject"), "{\"version\":1,\"reason\":\"  \"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.details[0].field").value("reason"));
    mvc.perform(json(post(SIGNS + "/s-1/reject"), "{\"version\":1,\"reason\":\"Sai mã\"}"))
        .andExpect(status().isNoContent());
    verify(reviews).reject(ACTOR, "s-1", new Reasoned(1, "Sai mã"));
  }

  @Test
  void keepAcceptsOptionalEdits() throws Exception {
    mvc.perform(json(post(SIGNS + "/s-1/keep"), "{\"version\":2,\"headingDeg\":180}"))
        .andExpect(status().isNoContent());
    verify(reviews).keep(ACTOR, "s-1", new Keep(2, null, null, 180));
  }

  @Test
  void removeNeedsSignEditPermission() throws Exception {
    String body = "{\"version\":3,\"reason\":\"Đã tháo\"}";
    mvc.perform(
            post(SIGNS + "/s-1/remove")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body)
                .with(as("review:decide")))
        .andExpect(status().isForbidden());
    mvc.perform(json(post(SIGNS + "/s-1/remove"), body)).andExpect(status().isNoContent());
    verify(reviews).remove(ACTOR, "s-1", new Reasoned(3, "Đã tháo"));
  }

  @Test
  void createReturnsIdAndVersion() throws Exception {
    when(reviews.create(eq(ACTOR), any())).thenReturn(new Created("new-1", 0));

    mvc.perform(
            json(
                post(SIGNS),
                """
                {"signTypeCode":"P.102","location":{"lat":21.0,"lng":105.8},"headingDeg":null}
                """))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.id").value("new-1"))
        .andExpect(jsonPath("$.version").value(0));
    verify(reviews).create(ACTOR, new NewSign("P.102", new LocationInput(21.0, 105.8), null));
  }

  @Test
  void createWithoutLocationIsValidationError() throws Exception {
    mvc.perform(json(post(SIGNS), "{\"signTypeCode\":\"P.102\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.details[0].field").value("location"));
  }

  @Test
  void patchKeepsExplicitNullHeading() throws Exception {
    mvc.perform(json(patch(SIGNS + "/s-1"), "{\"version\":4,\"headingDeg\":null}"))
        .andExpect(status().isNoContent());

    ArgumentCaptor<Edit> edit = ArgumentCaptor.forClass(Edit.class);
    verify(reviews).edit(eq(ACTOR), eq("s-1"), edit.capture());
    assertThat(edit.getValue().version()).isEqualTo(4);
    assertThat(edit.getValue().headingDeg().isNull()).isTrue();
  }

  @Test
  void patchWithOmittedHeadingHasNoHeadingNode() throws Exception {
    mvc.perform(json(patch(SIGNS + "/s-1"), "{\"version\":4,\"signTypeCode\":\"P.1\"}"))
        .andExpect(status().isNoContent());

    ArgumentCaptor<Edit> edit = ArgumentCaptor.forClass(Edit.class);
    verify(reviews).edit(eq(ACTOR), eq("s-1"), edit.capture());
    Edit value = edit.getValue();
    assertThat(value.headingDeg() == null || value.headingDeg().isMissingNode()).isTrue();
  }

  private static MockHttpServletRequestBuilder json(
      MockHttpServletRequestBuilder request, String body) {
    return request.contentType(MediaType.APPLICATION_JSON).content(body).with(moderator());
  }
}
