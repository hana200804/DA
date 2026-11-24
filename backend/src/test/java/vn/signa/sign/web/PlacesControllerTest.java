package vn.signa.sign.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import vn.signa.sign.internal.places.PlacesConfiguration;
import vn.signa.sign.internal.places.PlacesRateLimiter;

@WebMvcTest(value = PlacesController.class, properties = "signa.places.provider=none")
@AutoConfigureMockMvc(addFilters = false)
@Import({PlacesConfiguration.class, PlacesRateLimiter.class})
class PlacesControllerTest {

  private static final String PATH = "/api/v1/places/search";
  private static final String IP = "203.0.113.7";

  @Autowired MockMvc mvc;
  @MockitoBean StringRedisTemplate redis;

  @Test
  void noneProviderReturnsEmptyItemsForValidQuery() throws Exception {
    when(redis.<List<Long>>execute(any(), eq(List.of("rl:places:ip:" + IP)), eq("60")))
        .thenReturn(List.of(1L, 60L));

    mvc.perform(
            get(PATH)
                .param("q", " " + "à".repeat(100) + " ")
                .with(request -> {
                  request.setRemoteAddr(IP);
                  return request;
                }))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items").isArray())
        .andExpect(jsonPath("$.items").isEmpty());
  }

  @ParameterizedTest
  @ValueSource(ints = {0, 1, 101})
  void rejectsQueriesOutsideAllowedLength(int length) throws Exception {
    mvc.perform(get(PATH).param("q", " " + "à".repeat(length) + " "))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.details[0].field").value("q"));
    verifyNoInteractions(redis);
  }

  @Test
  void rejectsMissingQuery() throws Exception {
    mvc.perform(get(PATH))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    verifyNoInteractions(redis);
  }

  @Test
  void limitsPerIpAndReturnsWindowTtl() throws Exception {
    var key = List.of("rl:places:ip:" + IP);
    when(redis.<List<Long>>execute(any(), eq(key), eq("60")))
        .thenReturn(List.of(30L, 14L))
        .thenReturn(List.of(31L, 13L));
    when(redis.<List<Long>>execute(any(), eq(List.of("rl:places:ip:203.0.113.8")), eq("60")))
        .thenReturn(List.of(1L, 60L));
    var request =
        get(PATH)
            .param("q", "Hà Nội")
            .with(r -> {
              r.setRemoteAddr(IP);
              return r;
            });

    mvc.perform(request).andExpect(status().isOk());
    mvc.perform(request)
        .andExpect(status().isTooManyRequests())
        .andExpect(header().string("Retry-After", "13"))
        .andExpect(jsonPath("$.code").value("RATE_LIMITED"));
    mvc.perform(
            get(PATH)
                .param("q", "Hà Nội")
                .with(r -> {
                  r.setRemoteAddr("203.0.113.8");
                  return r;
                }))
        .andExpect(status().isOk());
  }
}
