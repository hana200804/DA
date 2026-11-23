package vn.signa.identity.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import vn.signa.identity.internal.AccountService;
import vn.signa.identity.internal.AccountService.LoginResult;
import vn.signa.identity.internal.AccountService.Profile;
import vn.signa.identity.internal.RolePermissions;
import vn.signa.identity.internal.SessionService;

/**
 * UT-B11: ma trận vai trò × endpoint → mã trả về (docs/08, FR-30). Khung dạng bảng: mỗi endpoint
 * khai báo quyền cần ({@link Access}), mã mong đợi suy ra từ {@link #MATRIX}. Khách nhận {@code
 * 401}; thiếu quyền nhận {@code 403}; đủ quyền thì không phải {@code 401}/{@code 403}.
 *
 * <p><b>Các lát sau:</b> thêm dòng vào {@link #ENDPOINTS} và {@code @MockitoBean} cho phụ thuộc của
 * controller mới. Không sửa luật suy ra mã mong đợi.
 */
@WebMvcTest
@Import({
  SecurityConfig.class,
  SecurityErrorWriter.class,
  AccessDeniedAdvice.class,
  PermissionMatrixTest.ProbeController.class
})
class PermissionMatrixTest {

  /** Ma trận quyền theo docs/06 §4; {@code SchemaIT} đối chiếu cùng ma trận với CSDL. */
  static final Map<String, Set<String>> MATRIX;

  static {
    Set<String> user = Set.of("submission:create", "sign:rate", "report:create");
    Set<String> moderator = new TreeSet<>(user);
    moderator.addAll(
        Set.of(
            "review:decide",
            "report:resolve",
            "sign:edit",
            "audit:read",
            "stats:read",
            "recognition:compare"));
    Set<String> admin = new TreeSet<>(moderator);
    admin.addAll(Set.of("catalog:manage", "user:manage"));
    MATRIX = Map.of("USER", user, "MODERATOR", Set.copyOf(moderator), "ADMIN", Set.copyOf(admin));
  }

  static final String GUEST = "GUEST";
  static final List<String> ROLES = List.of(GUEST, "USER", "MODERATOR", "ADMIN");

  /** Quyền cần cho một endpoint. */
  record Access(String permission) {
    static final Access PUBLIC = new Access("PUBLIC");
    static final Access AUTHENTICATED = new Access("AUTHENTICATED");

    static Access permission(String code) {
      return new Access(code);
    }

    boolean isPublic() {
      return this == PUBLIC;
    }

    boolean allows(String role) {
      if (isPublic()) return true;
      if (role.equals(GUEST)) return false;
      return this == AUTHENTICATED || MATRIX.get(role).contains(permission);
    }
  }

  record Endpoint(HttpMethod method, String path, String body, Access access) {
    @Override
    public String toString() {
      return method + " " + path;
    }
  }

  private static final String REGISTER =
      "{\"email\":\"a@b.vn\",\"password\":\"12345678\",\"displayName\":\"A\"}";
  private static final String LOGIN =
      "{\"email\":\"a@b.vn\",\"password\":\"12345678\",\"client\":\"MOBILE\"}";

  static final List<Endpoint> ENDPOINTS =
      List.of(
          // Lát 1 · tài khoản (docs/07 §3)
          new Endpoint(HttpMethod.POST, "/api/v1/auth/register", REGISTER, Access.PUBLIC),
          new Endpoint(HttpMethod.POST, "/api/v1/auth/login", LOGIN, Access.PUBLIC),
          new Endpoint(HttpMethod.POST, "/api/v1/auth/logout", null, Access.AUTHENTICATED),
          new Endpoint(HttpMethod.GET, "/api/v1/me", null, Access.AUTHENTICATED),
          new Endpoint(
              HttpMethod.PATCH, "/api/v1/me", "{\"displayName\":\"B\"}", Access.AUTHENTICATED),
          new Endpoint(
              HttpMethod.PUT,
              "/api/v1/me/password",
              "{\"currentPassword\":\"cu-12345\",\"newPassword\":\"moi-12345\"}",
              Access.AUTHENTICATED),
          // Quy tắc công khai đặt sẵn cho lát 2–4; chưa có controller nên trả 404.
          new Endpoint(HttpMethod.GET, "/api/v1/sign-types", null, Access.PUBLIC),
          new Endpoint(HttpMethod.GET, "/api/v1/sign-categories", null, Access.PUBLIC),
          new Endpoint(HttpMethod.GET, "/api/v1/signs", null, Access.PUBLIC),
          new Endpoint(HttpMethod.GET, "/api/v1/recognition/models", null, Access.PUBLIC),
          new Endpoint(HttpMethod.POST, "/api/v1/recognition/detect", null, Access.PUBLIC),
          new Endpoint(HttpMethod.GET, "/actuator/health", null, Access.PUBLIC),
          // Đường dẫn chưa khai báo công khai thì cần đăng nhập.
          new Endpoint(HttpMethod.POST, "/api/v1/signs", null, Access.AUTHENTICATED),
          new Endpoint(HttpMethod.GET, "/api/v1/admin/signs", null, Access.AUTHENTICATED),
          // Tự kiểm: endpoint mẫu có @PreAuthorize chứng minh khung bắt được thiếu quyền.
          new Endpoint(
              HttpMethod.GET,
              "/api/v1/test-probe/review",
              null,
              Access.permission("review:decide")),
          new Endpoint(
              HttpMethod.GET,
              "/api/v1/test-probe/catalog",
              null,
              Access.permission("catalog:manage")));

  @Autowired MockMvc mvc;
  @MockitoBean AccountService accounts;
  @MockitoBean SessionService sessions;
  @MockitoBean RolePermissions rolePermissions;

  @BeforeEach
  void stubs() {
    MATRIX.forEach((role, perms) -> when(rolePermissions.of(role)).thenReturn(perms));
    for (String role : MATRIX.keySet()) {
      WebTestSessions.signIn(sessions, role);
    }
    Profile profile = new Profile("u-1", "a@b.vn", "A", "USER", List.of());
    when(accounts.register(anyString(), anyString(), anyString())).thenReturn(profile);
    when(accounts.login(anyString(), anyString(), any(), anyString()))
        .thenReturn(new LoginResult("tok", Instant.parse("2026-12-01T00:00:00Z"), profile));
    when(accounts.profile(anyString())).thenReturn(profile);
    when(accounts.rename(anyString(), anyString())).thenReturn(profile);
  }

  static Stream<Arguments> cases() {
    return ENDPOINTS.stream().flatMap(e -> ROLES.stream().map(role -> Arguments.of(e, role)));
  }

  @ParameterizedTest(name = "{0} as {1}")
  @MethodSource("cases")
  void statusFollowsMatrix(Endpoint endpoint, String role) throws Exception {
    MockHttpServletRequestBuilder request = request(endpoint.method(), endpoint.path());
    if (endpoint.body() != null) {
      request.contentType(MediaType.APPLICATION_JSON).content(endpoint.body());
    }
    if (!role.equals(GUEST)) {
      request.header(HttpHeaders.AUTHORIZATION, "Bearer " + WebTestSessions.token(role));
    }

    int status = mvc.perform(request).andReturn().getResponse().getStatus();

    if (endpoint.access().allows(role)) {
      assertThat(status).as("%s với %s", endpoint, role).isNotIn(401, 403);
    } else if (role.equals(GUEST)) {
      assertThat(status).as("%s với khách", endpoint).isEqualTo(401);
    } else {
      assertThat(status).as("%s với %s", endpoint, role).isEqualTo(403);
    }
  }

  @Test
  void probesCoverBothAllowedAndDenied() {
    assertThat(Access.permission("review:decide").allows("USER")).isFalse();
    assertThat(Access.permission("review:decide").allows("MODERATOR")).isTrue();
    assertThat(Access.permission("catalog:manage").allows("MODERATOR")).isFalse();
    assertThat(Access.permission("catalog:manage").allows("ADMIN")).isTrue();
  }

  /** Bảng "Dữ liệu khởi tạo" ở docs/06 §4 phải khớp {@link #MATRIX}. */
  @Test
  void matrixMatchesDocs06() throws IOException {
    String doc = Files.readString(Path.of("..", "docs", "06-co-so-du-lieu.md"));
    String section = doc.substring(doc.indexOf("Dữ liệu khởi tạo"), doc.indexOf("### users"));
    Pattern row =
        Pattern.compile("^\\| `([a-z:]+)` \\|([^|]*)\\|([^|]*)\\|([^|]*)\\|", Pattern.MULTILINE);
    Map<String, Set<String>> documented = new TreeMap<>();
    List<String> roles = List.of("USER", "MODERATOR", "ADMIN");
    roles.forEach(r -> documented.put(r, new TreeSet<>()));
    List<String> permissions = new ArrayList<>();
    Matcher m = row.matcher(section);
    while (m.find()) {
      permissions.add(m.group(1));
      for (int i = 0; i < roles.size(); i++) {
        if (m.group(i + 2).contains("✓")) documented.get(roles.get(i)).add(m.group(1));
      }
    }

    assertThat(permissions).hasSize(11);
    roles.forEach(r ->
        assertThat(documented.get(r)).as(r).containsExactlyInAnyOrderElementsOf(MATRIX.get(r)));
  }

  @RestController
  static class ProbeController {
    @GetMapping("/api/v1/test-probe/review")
    @PreAuthorize("hasAuthority('review:decide')")
    String review() {
      return "ok";
    }

    @GetMapping("/api/v1/test-probe/catalog")
    @PreAuthorize("hasAuthority('catalog:manage')")
    String catalog() {
      return "ok";
    }
  }
}
