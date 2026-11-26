# Lát 1 · Tài khoản, phiên, phân quyền

Tài liệu ghi lại triển khai ban đầu của lát này. Cơ chế xác thực và thu hồi phiên
hiện hành được cập nhật ở [CSDL §10](../../06-co-so-du-lieu.md#10-redis).

> Kế hoạch thực thi theo [thiết kế triển khai](../thiet-ke-trien-khai.md) §5.3 và §6 hàng "1 · Tài khoản". Đánh dấu `- [x]` khi xong từng bước để người khác (hoặc Codex) làm tiếp được.

**Mục tiêu:** người dùng đăng ký, đăng nhập, đăng xuất, xem và sửa tài khoản, đổi mật khẩu; mọi request đi qua filter phiên Redis và kiểm tra theo quyền; web quản trị gọi được API thật qua backend chạy trong Compose.

**Yêu cầu:** FR-01, FR-30, NFR-10. **Ca nghiệm thu:** IT-B10, UT-B11 (khung, các lát sau thêm dòng), `docs/08 §7` phần phiên và log.

**Công nghệ:** như [lát 0](lat-0-khung.md), thêm Spring Security 7 (`spring-boot-starter-security`), BCrypt của Spring Security, Spring Data Redis (`StringRedisTemplate`), Jackson 3 (`tools.jackson`).

## Hợp đồng cho lát khác

Lát 2, 5 dùng `audit.api` song song với lát 1, nên chữ ký dưới đây giữ ổn định:

```java
package vn.signa.audit.api;

public interface AuditRecorder {
  /** Phải gọi trong giao dịch của bên gọi (propagation MANDATORY). requestId lấy từ MDC. */
  void record(AuditEntry entry);
}

public record AuditEntry(
    AuditEntityType entityType, // SIGN, REPORT, SIGN_TYPE, SIGN_CATEGORY, USER
    String entityId,            // ≤ 36 ký tự
    String action,              // ≤ 40 ký tự, theo bảng docs/06 §8
    ActorType actorType,        // USER (actorId bắt buộc) hoặc SYSTEM (actorId = null)
    String actorId,
    Map<String, ?> before,      // các trường thay đổi, ghi JSON; có thể null
    Map<String, ?> after,
    String reason) {            // ≤ 1000 ký tự; có thể null

  static AuditEntry bySystem(AuditEntityType, String entityId, String action,
      Map<String, ?> before, Map<String, ?> after, String reason);
  static AuditEntry byUser(String actorId, AuditEntityType, String entityId, String action,
      Map<String, ?> before, Map<String, ?> after, String reason);
}
```

Phân quyền cho phần 2 của các lát: `@PreAuthorize("hasAuthority('<mã quyền>')")`; lấy người dùng bằng `@AuthenticationPrincipal vn.signa.identity.api.AuthenticatedUser user` (`userId()`, `role()`, `permissions()`, `has(...)`). Thu hồi phiên: `vn.signa.identity.api.SessionRevoker.revokeAll(userId)`.

Quyền theo tiền tố `/api/v1/admin/**` trong `SecurityConfig.apiSecurity` (thêm ở lát 8, việc bổ sung). `@PreAuthorize` chỉ chạy sau khi Spring đọc và kiểm tham số, thân request, nên người thiếu quyền gửi dữ liệu sai nhận `400` thay vì `403`. Quy tắc theo URL chặn trước bước đó; `@PreAuthorize` trên controller vẫn giữ làm lớp thứ hai. Mỗi lát **thêm dòng** `requestMatchers(…).hasAuthority(…)` cho tiền tố của mình, đặt trước dòng mặc định `/api/v1/admin/**`, không sửa dòng có sẵn:

| Tiền tố | Method | Quyền | Lát | Trạng thái |
| --- | --- | --- | --- | --- |
| `/api/v1/admin/review-queue/**` | mọi method | `review:decide` | 6 | Đã có |
| `GET /api/v1/admin/signs/*`; `POST /api/v1/admin/signs/*/approve`, `/reject`, `/keep` | `GET`, `POST` | `review:decide` | 6 | Đã có |
| `POST /api/v1/admin/signs`, `/api/v1/admin/signs/*/remove`; `PATCH /api/v1/admin/signs/*` | `POST`, `PATCH` | `sign:edit` | 6 | Đã có |
| `/api/v1/admin/reports/**` | mọi method | `report:resolve` | 7 | Đã có |
| `/api/v1/admin/users/**` | mọi method | `user:manage` | 8 | Đã có |
| `/api/v1/admin/audit-logs/**` | mọi method | `audit:read` | 8 | Đã có |
| `/api/v1/admin/sign-types/**`, `/api/v1/admin/sign-categories/**` | mọi method | `catalog:manage` | 2 (thêm khi ghép ở lát 8) | Đã có |
| `/api/v1/admin/stats/**` | mọi method | `stats:read` | 9 | Đã có |
| `/api/v1/admin/recognition/**` | mọi method | `recognition:compare` | 9 | Đã có |
| `/api/v1/admin/**` còn lại | mọi method | `review:decide` (tối thiểu) | 8 (khi ghép lát 2–7) | Đã có |

Dòng mặc định nằm **sau** mọi dòng `/admin` cụ thể và trước `anyRequest()`, vì quy tắc nào khớp trước thì được dùng. Mọi quyền quản trị chỉ thuộc `MODERATOR`, `ADMIN`, và cả hai đều có `review:decide`, nên dòng này không chặn người đủ quyền trên CSDL thật. Lát mới thêm dòng của mình phía trên dòng mặc định. Nếu không thêm, endpoint vẫn chạy nhưng `@WebMvcTest` có vai trò giả thiếu `review:decide` sẽ nhận `403`. Trường hợp này đã gặp ở danh mục: `CatalogAdminControllerTest` chỉ cấp `catalog:manage`, nên phải thêm dòng `catalog:manage` riêng. Ở `signs`, dòng `review:decide` cho `approve`, `reject`, `keep` đặt trước dòng `sign:edit`.

Thêm dòng vào `PermissionMatrixTest` (UT-B11) ở phần 2 của mỗi lát:

1. Mỗi endpoint mới là một dòng `Endpoint.of(method, path, body, access)` trong `ENDPOINTS`. `access` là `Access.PUBLIC`, `Access.AUTHENTICATED` hoặc `Access.permission("<mã quyền>")`, khớp cột **Quyền** ở `docs/07`. `body` là thân request hợp lệ (hoặc `null`), để vai trò đủ quyền đi tới controller.
2. Thêm `@MockitoBean` cho phụ thuộc của controller mới và stub đủ để controller trả `2xx` hoặc lỗi nghiệp vụ `4xx`.
3. Dòng `Endpoint.reserved(...)` của lát mình (quy tắc công khai đặt sẵn) đổi sang `Endpoint.of(...)` khi đã có controller.
4. Không sửa `violation(...)`. Luật: khách `401`; thiếu quyền `403`; đủ quyền thì không được `401`, `403`; dòng `Endpoint.of` còn không được `404`, `405` hay `5xx`, nên controller không được nạp là test fail (ca tự kiểm `missingControllerIsCaught`).

## Ràng buộc chung

- Dùng `JdbcClient` và SQL viết tay. `identity` chỉ đụng `users`, `roles`, `permissions`, `role_permissions`; `audit` chỉ đụng `audit_logs` (`TableOwnershipTest`). Module khác chỉ gọi qua gói `api`.
- Thời gian lấy từ bean `Clock`.
- Không ghi mật khẩu hay token ra log. Redis chỉ giữ `sha256(token)`, không giữ token thô.
- Máy không có Docker: `./gradlew test` chạy unit test không container; `*IT` chỉ compile trên máy, chạy trên CI.
- Bean đọc CSDL hoặc Redis lúc khởi động gắn `@Profile("!migrate")`, vì chế độ `migrate` không có mật khẩu `signa_app`.

## Điểm cần soi khi review

1. Sai email và sai mật khẩu trả cùng `401` cùng thông điệp; email không tồn tại vẫn chạy BCrypt với một hash giả để thời gian phản hồi tương đương.
2. `LOCKED` và `WEB` thiếu `review:decide` chỉ trả `403` **sau khi** mật khẩu đúng, để không lộ trạng thái tài khoản cho người không biết mật khẩu.
3. Lần thử thứ 11 trong 15 phút theo `rl:login:{email}:{ip}` trả `429` kèm `Retry-After`, không kiểm tra mật khẩu nữa. Mỗi lần thử giữ chỗ bằng `INCR` **trước** khi so mật khẩu nên request song song không vượt giới hạn; đúng mật khẩu thì xoá bộ đếm. Nhập sai mật khẩu hiện tại khi đổi mật khẩu cũng giới hạn như vậy theo `rl:password:{userId}`.
4. Lỗi `401`, `403` do filter, entry point hay `@PreAuthorize` sinh ra đều có body `ErrorResponse` và `requestId`.
5. Đổi mật khẩu xoá mọi phiên khác của người đó, giữ phiên đang dùng; phiên cũ dùng lại trả `401` (IT-B10).
6. Mật khẩu dài quá 72 byte UTF-8 bị từ chối ở đăng ký và đổi mật khẩu, vì BCrypt chỉ dùng 72 byte đầu.
7. Quản trị viên đầu tiên không có mật khẩu mặc định; email đã tồn tại thì không nâng quyền tài khoản đó.

## Các nhiệm vụ

### Nhiệm vụ 0 · Kế hoạch

**Tạo:** `docs/backend/ke-hoach/lat-1-tai-khoan.md`.

- [x] Viết kế hoạch, markdownlint sạch, commit riêng.

### Nhiệm vụ 1 · Module `audit`

**Tạo:** `audit/api/AuditRecorder.java` (`record(AuditEntry)`), `audit/api/AuditEntry.java` (`entityType`, `entityId`, `action`, `actorType`, `actorId`, `before`, `after`, `reason`), `audit/api/AuditEntityType.java`, `audit/api/ActorType.java`, `audit/internal/JdbcAuditRecorder.java`, `src/test/.../audit/AuditEntryTest.java`, `src/test/.../audit/AuditRecorderIT.java`.

- [x] `AuditEntry` kiểm tra bất biến khi tạo: `SYSTEM` thì `actorId` rỗng, `USER` thì bắt buộc; `action` ≤ 40 ký tự; `reason` ≤ 1000 ký tự.
- [x] `JdbcAuditRecorder`: `@Transactional(propagation = MANDATORY)`, `INSERT INTO audit_logs` bằng `JdbcClient`; `before`, `after` ghi JSON bằng `JsonMapper` của Jackson 3; `requestId` lấy từ MDC; `created_at` từ `Clock`.
- [x] Unit test bất biến của `AuditEntry` và chuyển JSON.
- [x] `AuditRecorderIT`: ghi trong giao dịch thì có dòng đúng `request_id`; gọi ngoài giao dịch thì `IllegalTransactionStateException`. Chỉ compile trên máy.
- [x] Commit.

### Nhiệm vụ 2 · Phiên, filter và Spring Security

**Tạo:** `identity/api/SessionRevoker.java`, `identity/api/AuthenticatedUser.java`, `identity/internal/SessionTokens.java`, `Session.java`, `SessionStore.java`, `RedisSessionStore.java`, `SessionService.java`, `Client.java`, `RolePermissions.java`, `IdentityStartup.java`, `identity/web/SecurityConfig.java`, `SessionAuthenticationFilter.java`, `SessionAuthentication.java`, `SecurityErrorWriter.java`, `AccessDeniedAdvice.java`; sửa `build.gradle.kts` (thêm ở cuối khối `dependencies`, kèm `// lát 1`), `SignaApplication.java` (loại `UserDetailsServiceAutoConfiguration`).

- [x] Thêm `spring-boot-starter-security` và gói test tương ứng; tắt `UserDetailsService` sinh mật khẩu mặc định.
- [x] `SessionTokens`: 32 byte từ `SecureRandom`, base64url không padding (43 ký tự); `hash(token)` là SHA-256 hex; token sai định dạng bị loại trước khi tra Redis.
- [x] `RedisSessionStore`: `session:{hash}` là hash `userId`, `role`, `client`, `createdAt` có TTL; `user-sessions:{userId}` là set các hash. Tra phiên thì gia hạn TTL theo `client` (`MOBILE` 30 ngày, `WEB` 8 giờ). Khi tạo phiên, dọn hash đã hết hạn khỏi set.
- [x] `SessionService` cài `SessionRevoker.revokeAll(userId)`, thêm `revoke(sessionId)` và `revokeOthers(userId, keepSessionId)`.
- [x] `RolePermissions` nạp `role_permissions` vào bộ nhớ khi khởi động (`IdentityStartup`, `@Profile("!migrate")`).
- [x] `SecurityConfig`: stateless, tắt CSRF, form login, HTTP Basic; `@EnableMethodSecurity`; filter đọc `Authorization: Bearer`, đặt principal `AuthenticatedUser` cùng quyền; `userId` vào MDC. Công khai: `POST /auth/register`, `POST /auth/login`, `/actuator/health/**`, `/api/v1/openapi.json`, Swagger UI; đặt sẵn `GET /sign-types/**`, `GET /sign-categories/**`, `GET /signs/**`, `GET /recognition/**`, `POST /recognition/detect`. Còn lại cần đăng nhập.
- [x] Entry point và access denied handler ghi `ErrorResponse` (kèm `WWW-Authenticate: Bearer` cho `401`); `identity.web.AccessDeniedAdvice` (chạy trước handler chung, không sửa `shared/`) ánh xạ `AccessDeniedException` của `@PreAuthorize` sang `403`, hoặc `401` nếu chưa đăng nhập.
- [x] Unit test: `SessionTokensTest`, `SessionServiceTest` (kho phiên giả trong bộ nhớ), `GlobalExceptionHandlerTest` thêm cấu hình Security mở mọi đường dẫn để test ánh xạ lỗi như cũ.
- [x] Commit.

### Nhiệm vụ 3 · API tài khoản

**Tạo:** `identity/internal/UserRepository.java`, `UserRecord.java`, `AccountService.java`, `LoginRateLimiter.java`, `AttemptCounter.java`, `RedisAttemptCounter.java`, `PasswordPolicy.java`, `PasswordConfig.java`, `identity/web/AuthController.java`, `MeController.java`, các DTO request và response.

- [x] `POST /api/v1/auth/register`: email trim và lowercase, `@Email`, ≤ 255; mật khẩu 8–72 ký tự và ≤ 72 byte UTF-8; `displayName` trim, 1–100. Vai trò `USER`. Trùng email `409 CONFLICT`. Trả `201` với hồ sơ như `/me`.
- [x] `POST /api/v1/auth/login` `{ email, password, client }` trả `{ token, expiresAt, user: { id, displayName, role, permissions } }`; thứ tự kiểm tra: giới hạn → mật khẩu (`401` chung) → `LOCKED` (`403`) → `WEB` cần `review:decide` (`403`) → tạo phiên, cập nhật `last_login_at`, xoá bộ đếm sai.
- [x] `LoginRateLimiter`: cửa sổ cố định `INCR` cùng `EXPIRE` 15 phút, giữ chỗ trước khi so mật khẩu; lần thứ 11 thì `429` với `Retry-After` là TTL còn lại. Thêm `rl:password:{userId}` cho đổi mật khẩu (sau rà soát bảo mật).
- [x] `POST /api/v1/auth/logout` thu hồi phiên hiện tại, trả `204`.
- [x] `GET /api/v1/me` trả thẳng `{ id, email, displayName, role, permissions }`; `PATCH /api/v1/me` `{ displayName }` trả hồ sơ mới.
- [x] `PUT /api/v1/me/password` `{ currentPassword, newPassword }`: sai mật khẩu cũ `422 BUSINESS_RULE`; xong thì thu hồi các phiên khác, trả `204`.
- [x] BCrypt cost 12.
- [x] Unit test: `PasswordPolicyTest`, validator của request, `LoginRateLimiterTest` (bộ đếm giả theo `Clock`), `AccountServiceTest`; `@WebMvcTest` cho `AuthController`, `MeController` với filter thật và dịch vụ giả.
- [x] Commit.

### Nhiệm vụ 4 · Quản trị viên đầu tiên

**Tạo:** `identity/internal/BootstrapAdmin.java`, `IdentityProperties.java` (`@ConfigurationProperties("signa.identity")`, khối `signa.identity.bootstrap-admin` ở cuối `application.yml`), `BootstrapAdminTest.java`.

- [x] Khi khởi động (`!migrate`): chưa có `ADMIN` và có đủ `SIGNA_BOOTSTRAP_ADMIN_EMAIL`, `SIGNA_BOOTSTRAP_ADMIN_PASSWORD` thì tạo tài khoản `ADMIN` và ghi `USER/ROLE_CHANGED` với `actor_type = SYSTEM` trong cùng giao dịch. Thiếu biến thì bỏ qua. Mật khẩu sai chính sách thì dừng khởi động. Email đã có thì ghi cảnh báo và bỏ qua.
- [x] Unit test các nhánh với repository và recorder giả.
- [x] Commit.

### Nhiệm vụ 5 · Ma trận quyền UT-B11

**Tạo:** `src/test/.../identity/web/PermissionMatrixTest.java` (cùng gói với `SecurityConfig`; `@WebMvcTest` nạp mọi controller).

- [x] Hằng ma trận vai trò × quyền; một ca đối chiếu với bảng ở `docs/06 §4` (đọc file Markdown). `SchemaIT` đã đối chiếu cùng ma trận với CSDL.
- [x] Khung bảng `endpoint → quyền cần` (`PUBLIC`, `AUTHENTICATED` hoặc mã quyền) và cờ đã có controller hay chưa; với mỗi vai trò và khách, suy ra mã mong đợi: khách `401`, thiếu quyền `403`, đủ quyền thì không phải `401`/`403`. Siết thêm (việc bổ sung): dòng đã có controller mà vai trò đủ quyền nhận `404`, `405` hoặc `5xx` thì fail; ca tự kiểm `missingControllerIsCaught` gọi một đường dẫn không có controller.
- [x] Dòng cho endpoint lát 1 và hai endpoint mẫu trong test có `@PreAuthorize` (`review:decide`, `catalog:manage`) để chứng minh khung bắt được thiếu quyền.
- [x] Commit.

### Nhiệm vụ 6 · Test tích hợp

**Tạo:** `src/test/.../identity/AuthIT.java`, `BootstrapAdminIT.java` (context riêng qua `@TestPropertySource`, để không sửa `support/Containers.java` dùng chung). Vai trò `MODERATOR` và trạng thái `LOCKED` trong IT đặt bằng `UPDATE users` với `signa_app`, vì lát này chưa có API quản trị người dùng.

- [x] IT-B10: phiên cũ sau khi đổi mật khẩu trả `401`, phiên hiện tại vẫn dùng được.
- [x] Đăng ký, đăng nhập, `/me`, đăng xuất; token sai, thiếu, đã thu hồi trả `401`.
- [x] Tài khoản `LOCKED` không đăng nhập được; `WEB` với `USER` trả `403`; `MODERATOR` đăng nhập `WEB` được; quản trị viên đầu tiên được tạo kèm lịch sử `SYSTEM`.
- [x] Sai 11 lần trả `429` kèm `Retry-After`.
- [x] Mật khẩu và token không xuất hiện trong log (`OutputCaptureExtension`).
- [x] Redis không có khoá chứa token thô.
- [x] Chỉ compile trên máy, chạy trên CI. Commit.

### Nhiệm vụ 7 · Dockerfile, Compose, tài liệu

**Tạo:** `backend/Dockerfile`, `backend/.dockerignore`; sửa `infra/compose.yaml`, `infra/.env.example`, `backend/README.md`, `docs/07-api.md`.

- [x] Dockerfile nhiều tầng: JDK build `bootJar`, tách lớp bằng `-Djarmode=tools`, ảnh JRE chạy bằng user không phải root.
- [x] Compose: service `migrate` (cùng image, profile `migrate` và `app`, tài khoản migrator) và `backend` (chỉ `SIGNA_DB_APP_*`, `depends_on: migrate` với `service_completed_successfully`), cổng `127.0.0.1:8080`.
- [x] `.env.example` thêm biến quản trị viên đầu tiên; README mô tả chạy backend trong Compose và API tài khoản.
- [x] `docs/07 §3` ghi rõ phần hành vi tài liệu chưa nói: mã trả về của từng endpoint, `422` khi sai mật khẩu cũ, giới hạn độ dài mật khẩu.
- [x] `./gradlew spotlessCheck test compileTestJava` sạch; markdownlint sạch; ghi "Tiến độ"; commit.

## Tiến độ

Ghi ở đây trạng thái cuối và việc còn dở khi dừng giữa chừng.

**2026-11-23.** NV0–NV7 đã commit trên `backend/1-identity`, chưa push.

- Trên máy: `./gradlew clean spotlessCheck test compileTestJava` thành công, 163/163 unit test đạt sau việc bổ sung (trong đó `PermissionMatrixTest` 68 ca: 16 endpoint × 4 vai trò, cùng 4 ca đối chiếu và tự kiểm; 5 ca `X-Forwarded-For` chạy Tomcat thật, không cần Docker). `markdownlint` sạch.
- Chỉ verify được trên CI (máy không có Docker): `AuthIT` (IT-B10, đăng ký đến đăng xuất, khoá, `WEB`, `429`, token sai, log, khoá Redis), `BootstrapAdminIT`, `AuditRecorderIT`; build `backend/Dockerfile` và chạy `infra/compose.yaml` với profile `migrate`, `app`. Đã thử trên máy: tách lớp `-Djarmode=tools` và `JarLauncher` khởi động được; YAML Compose parse được, chưa chạy `docker compose config`.
- Quyết định tự đưa ra:
  - Đăng ký trả `201` với hồ sơ như `/me`, không tự đăng nhập. Đăng xuất và đổi mật khẩu trả `204`, `PATCH /me` trả hồ sơ mới. Đã ghi vào `docs/07 §3`.
  - Mật khẩu còn giới hạn ≤ 72 byte UTF-8, vì BCrypt cắt sau 72 byte và Spring Security từ chối mật khẩu dài hơn.
  - `displayName` được trim. Ở endpoint công khai, token hỏng bị bỏ qua thay vì trả `401`.
  - Bộ đếm giữ chỗ bằng `INCR` trước khi so mật khẩu (sau rà soát bảo mật), nên lần thứ 11 trả `429` và đúng mật khẩu thì xoá bộ đếm. Thêm `rl:password:{userId}` cho đổi mật khẩu, khoá này chưa có trong `docs/06 §10`.
  - `LOCKED` và `WEB` thiếu quyền chỉ trả `403` khi mật khẩu đúng.
  - Quản trị viên đầu tiên không nâng quyền email đã có tài khoản; mật khẩu sai chính sách thì dừng khởi động.
  - Lỗi Redis khi tra phiên trả `500 INTERNAL_ERROR` cùng định dạng chung.
  - `migrate` thuộc cả profile `app`, vì Compose không khởi động phụ thuộc ở profile chưa bật. `docker compose up -d` vẫn chỉ dựng hạ tầng như lát 0.
- Theo quy tắc làm song song: không sửa `shared/`. Ánh xạ `AccessDeniedException` nằm ở `identity.web.AccessDeniedAdvice`. `GlobalExceptionHandlerTest` thêm một `SecurityFilterChain` mở mọi đường dẫn, vì có Spring Security thì test cũ nhận `401`. `SignaApplication` loại `UserDetailsServiceAutoConfiguration` để không ghi mật khẩu sinh ngẫu nhiên ra log. Phụ thuộc thêm ở cuối khối `dependencies` kèm `// lát 1`, cấu hình ở khối `signa.identity`. Không thêm migration.
- Việc còn lại: push, mở PR, chạy CI cho các `*IT`. `docs/06 §10` đã sửa theo code (việc bổ sung). Sơ đồ `docs/03 §6.6` đã sửa thành `session:{sha256(token)}` (việc bổ sung).
- Rủi ro:
  - Đã xử lý (việc bổ sung): `server.forward-headers-strategy` chuyển từ `framework` sang `native`. Tomcat chỉ tin `X-Forwarded-For` từ proxy khớp `SIGNA_TRUSTED_PROXIES` (mặc định là dải IP riêng). Còn lại: nginx lát 10 phải ghi đè `X-Forwarded-For $remote_addr`.
  - Web quản trị coi phiên hết hạn theo `expiresAt` lúc đăng nhập (8 giờ), dù server gia hạn khi dùng.
  - Tài khoản bị khoá vẫn giữ phiên cũ cho tới khi lát 8 gọi `SessionRevoker.revokeAll`.
  - Spring Security thêm `Cache-Control: no-cache, no-store` vào response. Lát 2 cần tắt header này cho `/sign-types` nếu muốn client cache theo ETag.
  - Ảnh `eclipse-temurin:21.0.13.1_2` chưa được build thử.
