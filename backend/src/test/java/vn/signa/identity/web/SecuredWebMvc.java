package vn.signa.identity.web;

import static org.mockito.Mockito.when;

import java.util.Set;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Import;
import vn.signa.identity.internal.RolePermissions;
import vn.signa.identity.internal.SessionService;

/**
 * Cho {@code @WebMvcTest} của module khác: nạp Spring Security thật của identity (filter phiên,
 * {@code @PreAuthorize}, lỗi {@code 401}/{@code 403}) và đăng nhập giả theo vai trò. Dùng kèm
 * {@code @MockitoBean SessionService} và {@code @MockitoBean RolePermissions}.
 */
@TestConfiguration(proxyBeanMethods = false)
@Import({SecurityConfig.class, SecurityErrorWriter.class, AccessDeniedAdvice.class})
public class SecuredWebMvc {

  /** Cho {@code role} đăng nhập với các quyền cho trước; trả header {@code Authorization}. */
  public static String signIn(
      SessionService sessions, RolePermissions rolePermissions, String role, Set<String> perms) {
    when(rolePermissions.of(role)).thenReturn(perms);
    return WebTestSessions.signIn(sessions, role);
  }

  /** Id người dùng của phiên giả theo vai trò. */
  public static String userId(String role) {
    return WebTestSessions.userId(role);
  }
}
