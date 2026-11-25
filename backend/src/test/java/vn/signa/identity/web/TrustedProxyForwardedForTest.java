package vn.signa.identity.web;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;

/**
 * Cấu hình mặc định tin proxy trong dải IP riêng (ở đây 127.0.0.1 đóng vai nginx), nên IP client
 * lấy từ {@code X-Forwarded-For}.
 */
@SpringBootTest(
    classes = ForwardedHeadersTestSupport.App.class,
    webEnvironment = WebEnvironment.RANDOM_PORT)
class TrustedProxyForwardedForTest extends ForwardedHeadersTestSupport {

  @Test
  void proxyHeaderGivesClientIp() throws Exception {
    assertThat(ipSeenByRateLimiter("203.0.113.9")).isEqualTo("203.0.113.9");
  }

  @Test
  void withoutHeaderUsesConnectionAddress() throws Exception {
    assertThat(ipSeenByRateLimiter(null)).isEqualTo("127.0.0.1");
  }

  /** Nếu proxy lỡ nối thêm thay vì ghi đè, vẫn lấy IP ngoài cùng bên phải không thuộc proxy. */
  @Test
  void appendedChainUsesRightmostNonProxyAddress() throws Exception {
    assertThat(ipSeenByRateLimiter("198.51.100.7, 203.0.113.9")).isEqualTo("203.0.113.9");
  }
}
