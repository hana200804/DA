package vn.signa.identity.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.AbstractMockHttpServletRequestBuilder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import vn.signa.audit.internal.AuditLogService;
import vn.signa.audit.internal.AuditPage;
import vn.signa.catalog.internal.CatalogAdminService;
import vn.signa.catalog.internal.CatalogQueryService;
import vn.signa.catalog.internal.CatalogResponses.AdminSignTypeList;
import vn.signa.catalog.internal.CatalogResponses.SignCategoryList;
import vn.signa.catalog.internal.CatalogResponses.SignTypeItem;
import vn.signa.catalog.internal.CatalogResponses.SignTypesSnapshot;
import vn.signa.identity.internal.AccountService;
import vn.signa.identity.internal.AccountService.LoginResult;
import vn.signa.identity.internal.AccountService.Profile;
import vn.signa.identity.internal.RolePermissions;
import vn.signa.identity.internal.SessionService;
import vn.signa.identity.internal.UserAdminService;
import vn.signa.identity.internal.UserPage;
import vn.signa.recognition.internal.DetectResult;
import vn.signa.recognition.internal.ModelList;
import vn.signa.recognition.internal.RecognitionService;
import vn.signa.sign.clustering.SubmissionStatus;
import vn.signa.sign.internal.SubmissionService;
import vn.signa.sign.internal.feedback.RatingService;
import vn.signa.sign.internal.feedback.ReportService;
import vn.signa.sign.internal.map.MapResult;
import vn.signa.sign.internal.map.MapService;
import vn.signa.sign.internal.map.SignDetail;
import vn.signa.sign.internal.review.ReviewDetails;
import vn.signa.sign.internal.review.ReviewQueryService;
import vn.signa.sign.internal.review.ReviewService;
import vn.signa.sign.internal.review.ReviewViews.Created;
import vn.signa.sign.internal.review.ReviewViews.QueuePage;

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
      HttpMethod method,
      String path,
      String body,
      MockMultipartFile file,
      Access access,
      boolean hasController) {

    /** Endpoint đã có controller. */
    static Endpoint of(HttpMethod method, String path, String body, Access access) {
      return new Endpoint(method, path, body, null, access, true);
    }

    static Endpoint withFile(
        HttpMethod method, String path, MockMultipartFile file, Access access) {
      return new Endpoint(method, path, null, file, access, true);
    }

    /** Quy tắc bảo mật đặt sẵn, chưa có controller. */
    static Endpoint reserved(HttpMethod method, String path, Access access) {
      return new Endpoint(method, path, null, null, access, false);
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
  private static final Access MANAGE_CATALOG = Access.permission("catalog:manage");
  private static final String ADMIN_TYPES = "/api/v1/admin/sign-types";
  private static final String ADMIN_CATEGORIES = "/api/v1/admin/sign-categories";
  // Lát 6
  private static final Access REVIEW = Access.permission("review:decide");
  private static final Access EDIT_SIGN = Access.permission("sign:edit");
  private static final String ADMIN_SIGNS = "/api/v1/admin/signs";

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
          // Lát 2 · tra cứu danh mục (docs/07 §4)
          Endpoint.of(HttpMethod.GET, "/api/v1/sign-types", null, Access.PUBLIC),
          Endpoint.of(HttpMethod.GET, "/api/v1/sign-types/P.102", null, Access.PUBLIC),
          Endpoint.of(HttpMethod.GET, "/api/v1/sign-categories", null, Access.PUBLIC),
          // Lát 2 · quản trị danh mục (docs/07 §8)
          Endpoint.of(HttpMethod.GET, ADMIN_TYPES + "?active=false", null, MANAGE_CATALOG),
          Endpoint.of(
              HttpMethod.POST,
              ADMIN_CATEGORIES,
              "{\"code\":\"OTHER_NEW\",\"name\":\"Biển khác\",\"sortOrder\":5}",
              MANAGE_CATALOG),
          Endpoint.of(
              HttpMethod.PATCH, ADMIN_CATEGORIES + "/OTHER", "{\"sortOrder\":5}", MANAGE_CATALOG),
          Endpoint.of(
              HttpMethod.POST,
              ADMIN_TYPES,
              "{\"code\":\"T.1\",\"categoryCode\":\"OTHER\",\"name\":\"Biển thử\"}",
              MANAGE_CATALOG),
          Endpoint.of(HttpMethod.PATCH, ADMIN_TYPES + "/T.1", "{\"active\":false}", MANAGE_CATALOG),
          Endpoint.withFile(
              HttpMethod.PUT,
              ADMIN_TYPES + "/T.1/icon",
              new MockMultipartFile(
                  "icon", "T.1.png", "image/png", HexFormat.of().parseHex("89504e470d0a1a0a")),
              MANAGE_CATALOG),
          // Lát 3 · nhận diện (docs/07 §5)
          Endpoint.of(HttpMethod.GET, "/api/v1/recognition/models", null, Access.PUBLIC),
          Endpoint.withFile(
              HttpMethod.POST,
              "/api/v1/recognition/detect",
              new MockMultipartFile(
                  "image", "a.png", "image/png", HexFormat.of().parseHex("89504e470d0a1a0a")),
              Access.PUBLIC),
          // Lát 4 · bản đồ (docs/07 §6)
          Endpoint.of(
              HttpMethod.GET, "/api/v1/signs?bbox=105.8,21.02,105.81,21.03", null, Access.PUBLIC),
          Endpoint.of(HttpMethod.GET, "/api/v1/signs/s-1", null, Access.PUBLIC),
          // Lát 5 · đề xuất (docs/07 §6)
          new Endpoint(
              HttpMethod.POST,
              "/api/v1/submissions",
              """
              {"clientId":"abcdb1d7-3813-44d1-a641-e474c16010fe","signTypeCode":"P.102",
               "confidence":0.9,"location":{"lat":21.04,"lng":105.8},"source":"CAMERA",
               "model":{"name":"yolov8n","version":"1.0","labelMapVersion":1},
               "capturedAt":"2026-11-24T00:00:00Z"}
              """,
              new MockMultipartFile(
                  "crop", "crop.jpg", "image/jpeg", HexFormat.of().parseHex("ffd8ff")),
              Access.permission("submission:create"),
              true),
          Endpoint.of(HttpMethod.GET, "/api/v1/me/submissions", null, Access.AUTHENTICATED),
          // Lát 6 · phê duyệt và sửa biển (docs/07 §8)
          Endpoint.of(HttpMethod.GET, "/api/v1/admin/review-queue", null, REVIEW),
          Endpoint.of(HttpMethod.GET, ADMIN_SIGNS + "/s-1", null, REVIEW),
          Endpoint.of(HttpMethod.POST, ADMIN_SIGNS + "/s-1/approve", "{\"version\":1}", REVIEW),
          Endpoint.of(
              HttpMethod.POST,
              ADMIN_SIGNS + "/s-1/reject",
              "{\"version\":1,\"reason\":\"Sai\"}",
              REVIEW),
          Endpoint.of(HttpMethod.POST, ADMIN_SIGNS + "/s-1/keep", "{\"version\":1}", REVIEW),
          Endpoint.of(
              HttpMethod.POST,
              ADMIN_SIGNS,
              "{\"signTypeCode\":\"P.102\",\"location\":{\"lat\":21.0,\"lng\":105.8},"
                  + "\"headingDeg\":null}",
              EDIT_SIGN),
          Endpoint.of(
              HttpMethod.PATCH,
              ADMIN_SIGNS + "/s-1",
              "{\"version\":1,\"headingDeg\":90}",
              EDIT_SIGN),
          Endpoint.of(
              HttpMethod.POST,
              ADMIN_SIGNS + "/s-1/remove",
              "{\"version\":1,\"reason\":\"Sai\"}",
              EDIT_SIGN),
          // Lát 7 · đánh giá và báo cáo (docs/07 §6, §8)
          Endpoint.of(
              HttpMethod.PUT,
              "/api/v1/signs/s-1/rating",
              "{\"value\":\"DENY\"}",
              Access.permission("sign:rate")),
          Endpoint.of(
              HttpMethod.POST,
              "/api/v1/signs/s-1/reports",
              "{\"reason\":\"NOT_EXIST\"}",
              Access.permission("report:create")),
          Endpoint.of(HttpMethod.GET, "/api/v1/me/reports", null, Access.AUTHENTICATED),
          Endpoint.of(
              HttpMethod.GET, "/api/v1/admin/reports", null, Access.permission("report:resolve")),
          Endpoint.of(
              HttpMethod.POST,
              "/api/v1/admin/reports/r-1/accept",
              "{\"action\":\"REMOVE\",\"version\":1}",
              Access.permission("report:resolve")),
          Endpoint.of(
              HttpMethod.POST,
              "/api/v1/admin/reports/r-1/dismiss",
              "{\"note\":\"Đã kiểm tra\"}",
              Access.permission("report:resolve")),
          // Lát 8 · lịch sử (docs/07 §8)
          Endpoint.of(
              HttpMethod.GET, "/api/v1/admin/audit-logs", null, Access.permission("audit:read")),
          // Lát 8 · người dùng (docs/07 §8)
          Endpoint.of(
              HttpMethod.GET, "/api/v1/admin/users", null, Access.permission("user:manage")),
          Endpoint.of(
              HttpMethod.PATCH,
              "/api/v1/admin/users/u-khac",
              "{\"status\":\"LOCKED\"}",
              Access.permission("user:manage")),
          // Actuator không nạp trong @WebMvcTest; ApplicationIT kiểm /actuator/health thật.
          Endpoint.reserved(HttpMethod.GET, "/actuator/health", Access.PUBLIC),
          // Đường dẫn chưa khai báo công khai thì cần đăng nhập.
          Endpoint.reserved(HttpMethod.POST, "/api/v1/signs", Access.AUTHENTICATED),
          // /admin chưa có quy tắc riêng rơi vào mặc định review:decide (lát 8).
          Endpoint.reserved(HttpMethod.GET, "/api/v1/admin/signs", REVIEW),
          Endpoint.reserved(HttpMethod.GET, "/api/v1/admin/chua-co", REVIEW),
          // Đặt sẵn cho lát 9: mặc định review:decide cho đúng các vai trò có quyền này.
          Endpoint.reserved(HttpMethod.GET, "/api/v1/admin/stats", Access.permission("stats:read")),
          Endpoint.reserved(
              HttpMethod.POST,
              "/api/v1/admin/recognition/compare",
              Access.permission("recognition:compare")),
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
  @MockitoBean CatalogQueryService catalog;
  @MockitoBean CatalogAdminService catalogAdmin;
  @MockitoBean SessionService sessions;
  @MockitoBean SubmissionService submissions;
  @MockitoBean RolePermissions rolePermissions;
  // Lát 3 · nhận diện
  @MockitoBean RecognitionService recognition;
  // Lát 4 · bản đồ
  @MockitoBean MapService map;
  // Lát 6 · phê duyệt
  @MockitoBean ReviewService reviews;
  @MockitoBean ReviewQueryService reviewQueries;
  // Lát 7 · đánh giá và báo cáo
  @MockitoBean RatingService ratings;
  @MockitoBean ReportService reports;
  @MockitoBean AuditLogService auditLogs;
  @MockitoBean UserAdminService userAdmin;

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
    when(recognition.models()).thenReturn(new ModelList("yolov8", List.of()));
    when(recognition.detect(any(), any(), any()))
        .thenReturn(
            new DetectResult(
                new DetectResult.ModelRef("yolov8", "yolov8s-v1.0", 1),
                new DetectResult.ImageSize(8, 8),
                1,
                List.of()));
    when(map.find(any())).thenReturn(new MapResult.Signs(List.of()));
    when(map.detail("s-1"))
        .thenReturn(
            Optional.of(
                new SignDetail(
                    "s-1",
                    "P.102",
                    "PROHIBITORY",
                    new MapResult.LatLng(21.0285, 105.8048),
                    null,
                    "APPROVED",
                    0.9,
                    0,
                    0,
                    Instant.parse("2026-01-15T08:30:12.345Z"),
                    null)));
    when(map.detail(anyString(), anyString()))
        .thenAnswer(call ->
            map.detail(call.getArgument(0, String.class))
                .map(sign -> new MapService.PersonalDetail(sign, null)));
    when(reports.create(anyString(), anyString(), any(), any()))
        .thenReturn(new ReportService.Receipt("r-1", ReportService.Status.PENDING));
    when(reports.mine(anyString(), anyInt(), anyInt()))
        .thenReturn(new ReportService.Page<>(List.of(), 0, 20, 0));
    when(reports.queue(any(), any(), anyInt(), anyInt()))
        .thenReturn(new ReportService.Page<>(List.of(), 0, 20, 0));
    when(submissions.submit(anyString(), any(), any()))
        .thenReturn(
            new SubmissionService.Result(
                new SubmissionService.Receipt("submission-1", SubmissionStatus.RECEIVED), true));
    when(submissions.list(anyString(), anyInt(), anyInt()))
        .thenReturn(new SubmissionService.Page(List.of(), 0, 20, 0));
    when(catalog.categories()).thenReturn(new SignCategoryList(List.of()));
    when(catalogAdmin.types(false)).thenReturn(new AdminSignTypeList(List.of()));
    when(catalog.signTypes())
        .thenReturn(SignTypesSnapshot.of("{\"labelMapVersion\":0,\"items\":[]}"));
    when(reviewQueries.queue(any())).thenReturn(new QueuePage(List.of(), 0, 20, 0));
    when(reviewQueries.detail("s-1")).thenReturn(Optional.of(ReviewDetails.sample("s-1")));
    when(reviews.create(anyString(), any())).thenReturn(new Created("s-2", 0));
    when(catalog.activeType("P.102"))
        .thenReturn(
            Optional.of(
                new SignTypeItem(
                    "P.102", "PROHIBITORY", "Cấm đi ngược chiều", null, null, null, true)));
    when(auditLogs.search(any())).thenReturn(new AuditPage<>(List.of(), 0, 20, 0));
    when(userAdmin.search(any())).thenReturn(new UserPage(List.of(), 0, 20, 0));
  }

  static Stream<Arguments> cases() {
    return ENDPOINTS.stream().flatMap(e -> ROLES.stream().map(role -> Arguments.of(e, role)));
  }

  @ParameterizedTest(name = "{0} as {1}")
  @MethodSource("cases")
  void statusFollowsMatrix(Endpoint endpoint, String role) throws Exception {
    AbstractMockHttpServletRequestBuilder<?> request =
        endpoint.file() == null
            ? request(endpoint.method(), endpoint.path())
            : multipart(endpoint.method(), endpoint.path()).file(endpoint.file());
    if (endpoint.file() != null && endpoint.body() != null) {
      request =
          multipart(endpoint.method(), endpoint.path())
              .file(endpoint.file())
              .file(
                  new MockMultipartFile(
                      "metadata",
                      "",
                      MediaType.APPLICATION_JSON_VALUE,
                      endpoint.body().getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    } else if (endpoint.body() != null) {
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
