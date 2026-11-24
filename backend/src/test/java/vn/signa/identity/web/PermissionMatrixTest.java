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
import vn.signa.audit.internal.AuditLogService;
import vn.signa.audit.internal.AuditPage;
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
 * <p><b>Các lát sau:</b> thêm dòng {@link Endpoint#of} vào {@link #ENDPOINTS} cho endpoint mới, kèm
 * thân request hợp lệ nếu có, và {@code @MockitoBean} cho phụ thuộc của controller mới. Dòng {@link
 * Endpoint#reserved} của lát mình đổi sang {@link Endpoint#of} khi có controller. Không sửa {@link
 * #violation}.
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
  static final String PROBE = "/api/v1/test-probe";
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

  /**
   * Một dòng ma trận. {@code hasController = true} khi endpoint đã có controller trong lát này: vai
   * trò đủ quyền phải nhận {@code 2xx} hoặc lỗi nghiệp vụ {@code 4xx}, không được {@code 404},
   * {@code 405}. {@code false} cho quy tắc đặt sẵn của lát sau, chưa có controller nên {@code 404}.
   */
  record Endpoint(
      HttpMethod method, String path, String body, Access access, boolean hasController) {

    /** Endpoint đã có controller. */
    static Endpoint of(HttpMethod method, String path, String body, Access access) {
      return new Endpoint(method, path, body, access, true);
    }

    /** Quy tắc bảo mật đặt sẵn, chưa có controller. */
    static Endpoint reserved(HttpMethod method, String path, Access access) {
      return new Endpoint(method, path, null, access, false);
    }

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
          Endpoint.of(HttpMethod.POST, "/api/v1/auth/register", REGISTER, Access.PUBLIC),
          Endpoint.of(HttpMethod.POST, "/api/v1/auth/login", LOGIN, Access.PUBLIC),
          Endpoint.of(HttpMethod.POST, "/api/v1/auth/logout", null, Access.AUTHENTICATED),
          Endpoint.of(HttpMethod.GET, "/api/v1/me", null, Access.AUTHENTICATED),
          Endpoint.of(
              HttpMethod.PATCH, "/api/v1/me", "{\"displayName\":\"B\"}", Access.AUTHENTICATED),
          Endpoint.of(
              HttpMethod.PUT,
              "/api/v1/me/password",
              "{\"currentPassword\":\"cu-12345\",\"newPassword\":\"moi-12345\"}",
              Access.AUTHENTICATED),
          // Lát 8 · lịch sử (docs/07 §8)
          Endpoint.of(
              HttpMethod.GET, "/api/v1/admin/audit-logs", null, Access.permission("audit:read")),
          // Quy tắc công khai đặt sẵn cho lát 2–4. Lát có controller đổi dòng sang Endpoint.of.
          Endpoint.reserved(HttpMethod.GET, "/api/v1/sign-types", Access.PUBLIC),
          Endpoint.reserved(HttpMethod.GET, "/api/v1/sign-categories", Access.PUBLIC),
          Endpoint.reserved(HttpMethod.GET, "/api/v1/signs", Access.PUBLIC),
          Endpoint.reserved(HttpMethod.GET, "/api/v1/recognition/models", Access.PUBLIC),
          Endpoint.reserved(HttpMethod.POST, "/api/v1/recognition/detect", Access.PUBLIC),
          // Actuator không nạp trong @WebMvcTest; ApplicationIT kiểm /actuator/health thật.
          Endpoint.reserved(HttpMethod.GET, "/actuator/health", Access.PUBLIC),
          // Đường dẫn chưa khai báo công khai thì cần đăng nhập.
          Endpoint.reserved(HttpMethod.POST, "/api/v1/signs", Access.AUTHENTICATED),
          Endpoint.reserved(HttpMethod.GET, "/api/v1/admin/signs", Access.AUTHENTICATED),
          // Tự kiểm: endpoint mẫu có @PreAuthorize chứng minh khung bắt được thiếu quyền.
          Endpoint.of(HttpMethod.GET, PROBE + "/review", null, Access.permission("review:decide")),
          Endpoint.of(
              HttpMethod.GET, PROBE + "/catalog", null, Access.permission("catalog:manage")));

  /**
   * Luật suy ra mã mong đợi; trả lý do sai hoặc {@code null} nếu đúng. Các lát sau không sửa luật.
   */
  static String violation(Endpoint endpoint, String role, int status) {
    if (!endpoint.access().allows(role)) {
      int expected = role.equals(GUEST) ? 401 : 403;
      return status == expected ? null : "cần " + expected + ", nhận " + status;
    }
    if (status == 401 || status == 403) {
      return "đủ quyền nhưng nhận " + status;
    }
    if (endpoint.hasController()) {
      boolean ok = (status >= 200 && status < 300) || (status >= 400 && status < 500);
      if (!ok || status == 404 || status == 405) {
        return "đã có controller, cần 2xx hoặc lỗi nghiệp vụ 4xx, nhận "
            + status
            + (status == 404 || status == 405 ? " (controller chưa được nạp?)" : "");
      }
    }
    return null;
  }

  @Autowired MockMvc mvc;
  @MockitoBean AccountService accounts;
  @MockitoBean SessionService sessions;
  @MockitoBean RolePermissions rolePermissions;
  @MockitoBean AuditLogService auditLogs;

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
    when(auditLogs.search(any())).thenReturn(new AuditPage<>(List.of(), 0, 20, 0));
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

    assertThat(violation(endpoint, role, status)).as("%s với %s", endpoint, role).isNull();
  }

  /**
   * Tự kiểm: một dòng khai báo có controller nhưng controller không được nạp thì khung phải báo
   * lỗi. Gọi thật một đường dẫn không có controller nào.
   */
  @Test
  void missingControllerIsCaught() throws Exception {
    Endpoint missing = Endpoint.of(HttpMethod.GET, PROBE + "/missing", null, Access.AUTHENTICATED);
    int status =
        mvc.perform(
                request(missing.method(), missing.path())
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + WebTestSessions.token("USER")))
            .andReturn()
            .getResponse()
            .getStatus();

    assertThat(status).isEqualTo(404);
    assertThat(violation(missing, "USER", status)).contains("controller chưa được nạp");
    assertThat(violation(missing, GUEST, 401)).isNull();
  }

  @Test
  void ruleRejectsWrongStatuses() {
    Endpoint review =
        Endpoint.of(HttpMethod.GET, PROBE + "/review", null, Access.permission("review:decide"));
    assertThat(violation(review, "MODERATOR", 200)).isNull();
    assertThat(violation(review, "MODERATOR", 422)).isNull();
    assertThat(violation(review, "MODERATOR", 405)).isNotNull();
    assertThat(violation(review, "MODERATOR", 500)).isNotNull();
    assertThat(violation(review, "USER", 404)).isNotNull();
    assertThat(violation(review, "USER", 403)).isNull();
    assertThat(violation(review, GUEST, 403)).isNotNull();
    Endpoint reserved = Endpoint.reserved(HttpMethod.GET, "/api/v1/signs", Access.PUBLIC);
    assertThat(violation(reserved, GUEST, 404)).isNull();
    assertThat(violation(reserved, GUEST, 401)).isNotNull();
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
    @GetMapping(PROBE + "/review")
    @PreAuthorize("hasAuthority('review:decide')")
    String review() {
      return "ok";
    }

    @GetMapping(PROBE + "/catalog")
    @PreAuthorize("hasAuthority('catalog:manage')")
    String catalog() {
      return "ok";
    }
  }
}
