package vn.signa.identity.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.http.converter.autoconfigure.HttpMessageConvertersAutoConfiguration;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.tomcat.autoconfigure.servlet.TomcatServletWebServerAutoConfiguration;
import org.springframework.boot.validation.autoconfigure.ValidationAutoConfiguration;
import org.springframework.boot.webmvc.autoconfigure.DispatcherServletAutoConfiguration;
import org.springframework.boot.webmvc.autoconfigure.WebMvcAutoConfiguration;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import vn.signa.identity.internal.AccountService;
import vn.signa.identity.internal.AccountService.LoginResult;
import vn.signa.identity.internal.AccountService.Profile;
import vn.signa.identity.internal.Client;
import vn.signa.shared.web.GlobalExceptionHandler;
import vn.signa.shared.web.RequestIdFilter;

/**
 * Tomcat thật với {@code application.yml} (cấu hình {@code server.forward-headers-strategy} và
 * {@code server.tomcat.remoteip}), chỉ nạp {@link AuthController}; không cần MySQL hay Redis. IP mà
 * controller đưa cho {@link AccountService#login} chính là IP trong khoá {@code
 * rl:login:{email}:{ip}}.
 */
abstract class ForwardedHeadersTestSupport {

  // Không dùng @SpringBootConfiguration: @WebMvcTest cùng gói sẽ lấy nhầm lớp này làm cấu hình gốc.
  @Configuration(proxyBeanMethods = false)
  @ImportAutoConfiguration({
    TomcatServletWebServerAutoConfiguration.class,
    DispatcherServletAutoConfiguration.class,
    WebMvcAutoConfiguration.class,
    HttpMessageConvertersAutoConfiguration.class,
    JacksonAutoConfiguration.class,
    ValidationAutoConfiguration.class
  })
  @Import({AuthController.class, GlobalExceptionHandler.class, RequestIdFilter.class})
  static class App {}

  static final String EMAIL = "a@b.vn";

  @LocalServerPort int port;
  @MockitoBean AccountService accounts;

  private final HttpClient http = HttpClient.newHttpClient();

  @BeforeEach
  void stubLogin() {
    when(accounts.login(anyString(), anyString(), any(), anyString()))
        .thenReturn(
            new LoginResult(
                "tok",
                Instant.parse("2026-12-01T00:00:00Z"),
                new Profile("u-1", EMAIL, "A", "USER", List.of())));
  }

  /** Đăng nhập kèm {@code X-Forwarded-For} rồi trả IP mà giới hạn đăng nhập nhận được. */
  String ipSeenByRateLimiter(String forwardedFor) throws Exception {
    HttpRequest.Builder request =
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1/auth/login"))
            .header("Content-Type", "application/json")
            .POST(
                HttpRequest.BodyPublishers.ofString(
                    "{\"email\":\""
                        + EMAIL
                        + "\",\"password\":\"12345678\",\"client\":\"MOBILE\"}"));
    if (forwardedFor != null) {
      request.header("X-Forwarded-For", forwardedFor);
    }
    HttpResponse<String> response =
        http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    if (response.statusCode() != 200) {
      throw new AssertionError("Đăng nhập trả " + response.statusCode() + ": " + response.body());
    }
    ArgumentCaptor<String> ip = ArgumentCaptor.forClass(String.class);
    verify(accounts).login(eq(EMAIL), eq("12345678"), eq(Client.MOBILE), ip.capture());
    return ip.getValue();
  }
}
