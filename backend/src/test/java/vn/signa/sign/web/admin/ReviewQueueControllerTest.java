package vn.signa.sign.web.admin;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static vn.signa.sign.web.admin.AdminRequests.as;
import static vn.signa.sign.web.admin.AdminRequests.moderator;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import vn.signa.identity.web.WithApiSecurity;
import vn.signa.sign.internal.review.ReviewDetails;
import vn.signa.sign.internal.review.ReviewQueries;
import vn.signa.sign.internal.review.ReviewQueryService;
import vn.signa.sign.internal.review.ReviewViews.LatLng;
import vn.signa.sign.internal.review.ReviewViews.QueueItem;
import vn.signa.sign.internal.review.ReviewViews.QueuePage;

@WebMvcTest(ReviewQueueController.class)
@WithApiSecurity
class ReviewQueueControllerTest {

  private static final String QUEUE = "/api/v1/admin/review-queue";

  @Autowired MockMvc mvc;
  @MockitoBean ReviewQueryService queries;

  @Test
  void returnsPageInDocumentedShape() throws Exception {
    QueueItem item =
        new QueueItem(
            "c1a9",
            "P.123a",
            "Cấm rẽ trái",
            "PROHIBITORY",
            new LatLng(21.0285, 105.8048),
            92,
            "PENDING",
            0.82,
            "APPROVE",
            2,
            List.of(),
            ReviewDetails.CREATED);
    when(queries.queue(any())).thenReturn(new QueuePage(List.of(item), 0, 20, 1));

    mvc.perform(get(QUEUE).with(as("review:decide")))
        .andExpect(status().isOk())
        .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
        .andExpect(jsonPath("$.page").value(0))
        .andExpect(jsonPath("$.size").value(20))
        .andExpect(jsonPath("$.total").value(1))
        .andExpect(jsonPath("$.items[0].id").value("c1a9"))
        .andExpect(jsonPath("$.items[0].signName").value("Cấm rẽ trái"))
        .andExpect(jsonPath("$.items[0].location.lat").value(21.0285))
        .andExpect(jsonPath("$.items[0].suggestion").value("APPROVE"))
        .andExpect(jsonPath("$.items[0].distinctSubmitters").value(2))
        .andExpect(jsonPath("$.items[0].createdAt").value("2026-01-15T08:30:12.345Z"));
    verify(queries).queue(ReviewQueries.parse(null, null, null, null, null, null));
  }

  @Test
  void passesFiltersToParser() throws Exception {
    when(queries.queue(any())).thenReturn(new QueuePage(List.of(), 1, 5, 0));

    mvc.perform(
            get(QUEUE)
                .param("status", "PENDING")
                .param("suggestion", "INSPECT")
                .param("categories", "PROHIBITORY")
                .param("bbox", "105.8,21.02,105.81,21.03")
                .param("page", "1")
                .param("size", "5")
                .with(moderator()))
        .andExpect(status().isOk());
    verify(queries)
        .queue(
            ReviewQueries.parse(
                "PENDING", "INSPECT", "PROHIBITORY", "105.8,21.02,105.81,21.03", "1", "5"));
  }

  @Test
  void invalidParametersAreValidationErrors() throws Exception {
    mvc.perform(get(QUEUE).param("size", "0").param("status", "REMOVED").with(moderator()))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
        .andExpect(jsonPath("$.details[0].field").value("status"))
        .andExpect(jsonPath("$.details[1].field").value("size"));
    verifyNoInteractions(queries);
  }

  @Test
  void needsReviewPermission() throws Exception {
    mvc.perform(get(QUEUE).with(as("sign:edit"))).andExpect(status().isForbidden());
    mvc.perform(get(QUEUE)).andExpect(status().isUnauthorized());
  }
}
