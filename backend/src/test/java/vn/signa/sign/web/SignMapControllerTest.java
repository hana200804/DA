package vn.signa.sign.web;

import static org.hamcrest.Matchers.hasItem;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import vn.signa.shared.web.GlobalExceptionHandler;
import vn.signa.shared.web.RequestIdFilter;
import vn.signa.sign.internal.map.BoundingBox;
import vn.signa.sign.internal.map.MapQuery;
import vn.signa.sign.internal.map.MapResult;
import vn.signa.sign.internal.map.MapResult.LatLng;
import vn.signa.sign.internal.map.MapResult.MapCluster;
import vn.signa.sign.internal.map.MapResult.MapSign;
import vn.signa.sign.internal.map.MapService;
import vn.signa.sign.internal.map.SignDetail;

@WebMvcTest(controllers = SignMapController.class)
@Import({GlobalExceptionHandler.class, RequestIdFilter.class})
class SignMapControllerTest {

  @Autowired MockMvc mvc;
  @MockitoBean MapService service;

  @Test
  void signsModeMatchesApiShape() throws Exception {
    MapQuery expected =
        new MapQuery(
            new BoundingBox(105.8, 21.02, 105.81, 21.03),
            List.of("PROHIBITORY", "WARNING"),
            List.of("P.123a"),
            14);
    when(service.find(expected))
        .thenReturn(
            new MapResult.Signs(
                List.of(
                    new MapSign(
                        "c1a9",
                        "P.123a",
                        "PROHIBITORY",
                        new LatLng(21.0285, 105.8048),
                        92,
                        "APPROVED",
                        0.91))));

    mvc.perform(
            get("/api/v1/signs")
                .param("bbox", "105.8,21.02,105.81,21.03")
                .param("categories", "PROHIBITORY,WARNING")
                .param("signTypeCodes", "P.123a"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.mode").value("SIGNS"))
        .andExpect(jsonPath("$.items[0].id").value("c1a9"))
        .andExpect(jsonPath("$.items[0].signTypeCode").value("P.123a"))
        .andExpect(jsonPath("$.items[0].categoryCode").value("PROHIBITORY"))
        .andExpect(jsonPath("$.items[0].location.lat").value(21.0285))
        .andExpect(jsonPath("$.items[0].location.lng").value(105.8048))
        .andExpect(jsonPath("$.items[0].headingDeg").value(92))
        .andExpect(jsonPath("$.items[0].status").value("APPROVED"))
        .andExpect(jsonPath("$.items[0].confidence").value(0.91));
  }

  @Test
  void clustersModeMatchesApiShape() throws Exception {
    MapQuery expected = new MapQuery(new BoundingBox(0, 0, 1, 1), List.of(), List.of(), 3);
    when(service.find(expected))
        .thenReturn(new MapResult.Clusters(List.of(new MapCluster(new LatLng(0.5, 0.25), 600))));

    mvc.perform(get("/api/v1/signs").param("bbox", "0,0,1,1").param("zoom", "3"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.mode").value("CLUSTERS"))
        .andExpect(jsonPath("$.items[0].location.lat").value(0.5))
        .andExpect(jsonPath("$.items[0].location.lng").value(0.25))
        .andExpect(jsonPath("$.items[0].count").value(600));
  }

  @Test
  void invalidParametersAre400WithDetails() throws Exception {
    mvc.perform(get("/api/v1/signs").param("bbox", "179,10,-179,11").param("zoom", "40"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
        .andExpect(jsonPath("$.details[*].field").value(hasItem("bbox")))
        .andExpect(jsonPath("$.details[*].field").value(hasItem("zoom")))
        .andExpect(jsonPath("$.requestId").isNotEmpty());
    verify(service, never()).find(any());
  }

  @Test
  void missingBboxIs400WithDetails() throws Exception {
    mvc.perform(get("/api/v1/signs"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
        .andExpect(jsonPath("$.details[0].field").value("bbox"));
  }

  @Test
  void detailMatchesApiShape() throws Exception {
    when(service.detail("s1"))
        .thenReturn(
            Optional.of(
                new SignDetail(
                    "s1",
                    "P.123a",
                    "PROHIBITORY",
                    new LatLng(21.0285, 105.8048),
                    null,
                    "UNDER_REVIEW",
                    0.5,
                    4,
                    2,
                    Instant.parse("2026-01-15T08:30:12.345Z"),
                    null)));

    mvc.perform(get("/api/v1/signs/s1"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value("s1"))
        .andExpect(jsonPath("$.location.lat").value(21.0285))
        .andExpect(jsonPath("$.location.lng").value(105.8048))
        .andExpect(jsonPath("$.headingDeg").isEmpty())
        .andExpect(jsonPath("$.status").value("UNDER_REVIEW"))
        .andExpect(jsonPath("$.confirmCount").value(4))
        .andExpect(jsonPath("$.denyCount").value(2))
        .andExpect(jsonPath("$.updatedAt").value("2026-01-15T08:30:12.345Z"))
        .andExpect(jsonPath("$.coverCropUrl").isEmpty());
  }

  @Test
  void unknownOrUnpublishedDetailIs404() throws Exception {
    when(service.detail("nope")).thenReturn(Optional.empty());

    mvc.perform(get("/api/v1/signs/nope"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("NOT_FOUND"));
  }
}
