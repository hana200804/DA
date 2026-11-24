package vn.signa.identity.web;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;

/**
 * Request đến từ địa chỉ không phải proxy tin cậy: {@code X-Forwarded-For} giả bị bỏ qua, giới hạn
 * đăng nhập tính theo IP thật. Test kết nối từ 127.0.0.1, nên chỉ tin một proxy khác để 127.0.0.1
 * đóng vai client bên ngoài.
 */
@SpringBootTest(
    classes = ForwardedHeadersTestSupport.App.class,
    webEnvironment = WebEnvironment.RANDOM_PORT,
    properties = "SIGNA_TRUSTED_PROXIES=10\\.9\\.9\\.9")
class UntrustedForwardedForTest extends ForwardedHeadersTestSupport {

  @Test
  void spoofedHeaderFromNonProxyIsIgnored() throws Exception {
    assertThat(ipSeenByRateLimiter("203.0.113.9")).isEqualTo("127.0.0.1");
  }

  @Test
  void spoofedChainFromNonProxyIsIgnored() throws Exception {
    assertThat(ipSeenByRateLimiter("198.51.100.7, 10.9.9.9")).isEqualTo("127.0.0.1");
  }
}
