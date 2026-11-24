package vn.signa.shared.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import vn.signa.shared.error.ApiException;
import vn.signa.shared.error.ErrorCode;

@WebMvcTest(controllers = GlobalExceptionHandlerTest.FakeController.class)
@Import({
  GlobalExceptionHandler.class,
  RequestIdFilter.class,
  GlobalExceptionHandlerTest.FakeController.class,
  GlobalExceptionHandlerTest.OpenSecurity.class
})
class GlobalExceptionHandlerTest {

  @Autowired MockMvc mvc;

  @Test
  void apiExceptionUsesItsCodeAndStatus() throws Exception {
    mvc.perform(get("/fake/not-found").header(RequestIdFilter.HEADER, "abc-123"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("NOT_FOUND"))
        .andExpect(jsonPath("$.message").value("Không tìm thấy tài nguyên"))
        .andExpect(jsonPath("$.details").isArray())
        .andExpect(jsonPath("$.requestId").value("abc-123"))
        .andExpect(header().string(RequestIdFilter.HEADER, "abc-123"));
  }

  @Test
  void invalidBodyListsFieldIssues() throws Exception {
    mvc.perform(
            post("/fake/body").contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
        .andExpect(jsonPath("$.details[0].field").value("name"));
  }

  @Test
  void malformedJsonIsValidationError() throws Exception {
    mvc.perform(post("/fake/body").contentType(MediaType.APPLICATION_JSON).content("{oops"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
  }

  @Test
  void unexpectedErrorHidesInternals() throws Exception {
    mvc.perform(get("/fake/boom"))
        .andExpect(status().isInternalServerError())
        .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
        .andExpect(jsonPath("$.message").value("Lỗi hệ thống"));
  }

  @Test
  void rateLimitedCarriesRetryAfter() throws Exception {
    mvc.perform(get("/fake/limited"))
        .andExpect(status().isTooManyRequests())
        .andExpect(header().string("Retry-After", "30"))
        .andExpect(jsonPath("$.code").value("RATE_LIMITED"));
  }

  @Test
  void businessRuleIs422() throws Exception {
    mvc.perform(get("/fake/rule"))
        .andExpect(status().isUnprocessableContent())
        .andExpect(jsonPath("$.code").value("BUSINESS_RULE"))
        .andExpect(jsonPath("$.message").value("labelMapVersion không hỗ trợ"));
  }

  /**
   * Từ lát 1 có Spring Security. Mở mọi đường dẫn: test này chỉ kiểm tra ánh xạ lỗi, phân quyền
   * kiểm ở {@code identity}.
   */
  @TestConfiguration
  static class OpenSecurity {
    @Bean
    SecurityFilterChain open(HttpSecurity http) throws Exception {
      return http.csrf(AbstractHttpConfigurer::disable)
          .authorizeHttpRequests(a -> a.anyRequest().permitAll())
          .build();
    }
  }

  @RestController
  static class FakeController {

    record Body(@NotBlank String name) {}

    @GetMapping("/fake/not-found")
    void notFound() {
      throw ApiException.notFound();
    }

    @PostMapping("/fake/body")
    void body(@Valid @RequestBody Body body) {}

    @GetMapping("/fake/boom")
    void boom() {
      throw new IllegalStateException("bí mật nội bộ");
    }

    @GetMapping("/fake/limited")
    void limited() {
      throw ApiException.rateLimited(30);
    }

    @GetMapping("/fake/rule")
    void rule() {
      throw new ApiException(ErrorCode.BUSINESS_RULE, "labelMapVersion không hỗ trợ");
    }
  }
}
