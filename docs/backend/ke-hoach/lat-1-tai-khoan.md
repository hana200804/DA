# Lát 1 · Tài khoản, phiên, phân quyền

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

Phân quyền cho phần 2 của các lát: `@PreAuthorize("hasAuthority('<mã quyền>')")`; lấy người dùng bằng `@AuthenticationPrincipal vn.signa.identity.api.AuthenticatedUser user` (`userId()`, `role()`, `permissions()`, `has(...)`). Thu hồi phiên: `vn.signa.identity.api.SessionRevoker.revokeAll(userId)`. Endpoint mới thêm dòng vào `PermissionMatrixTest`, không sửa luật của khung.

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
- [x] Khung bảng `endpoint → quyền cần` (`PUBLIC`, `AUTHENTICATED` hoặc mã quyền); với mỗi vai trò và khách, suy ra mã mong đợi: khách `401`, thiếu quyền `403`, đủ quyền thì không phải `401`/`403`.
- [x] Dòng cho endpoint lát 1 và hai endpoint mẫu trong test có `@PreAuthorize` (`review:decide`, `catalog:manage`) để chứng minh khung bắt được thiếu quyền.
- [x] Commit.

### Nhiệm vụ 6 · Test tích hợp

**Tạo:** `src/test/.../identity/AuthIT.java`, sửa `support/Containers.java`.

- [ ] IT-B10: phiên cũ sau khi đổi mật khẩu trả `401`, phiên hiện tại vẫn dùng được.
- [ ] Đăng ký, đăng nhập, `/me`, đăng xuất; token sai, thiếu, đã thu hồi trả `401`.
- [ ] Tài khoản `LOCKED` không đăng nhập được; `WEB` với `USER` trả `403`; quản trị viên đầu tiên đăng nhập `WEB` được.
- [ ] Sai 11 lần trả `429` kèm `Retry-After`.
- [ ] Mật khẩu và token không xuất hiện trong log (`OutputCaptureExtension`).
- [ ] Redis không có khoá chứa token thô.
- [ ] Chỉ compile trên máy, chạy trên CI. Commit.

### Nhiệm vụ 7 · Dockerfile, Compose, tài liệu

**Tạo:** `backend/Dockerfile`, `backend/.dockerignore`; sửa `infra/compose.yaml`, `infra/.env.example`, `backend/README.md`, `docs/07-api.md`.

- [ ] Dockerfile nhiều tầng: JDK build `bootJar`, tách lớp bằng `-Djarmode=tools`, ảnh JRE chạy bằng user không phải root.
- [ ] Compose: service `migrate` (cùng image, profile `migrate`, tài khoản migrator) và `backend` (chỉ `SIGNA_DB_APP_*`, `depends_on: migrate` với `service_completed_successfully`), cổng `127.0.0.1:8080`.
- [ ] `.env.example` thêm biến quản trị viên đầu tiên; README mô tả chạy backend trong Compose và API tài khoản.
- [ ] `docs/07 §3` ghi rõ phần hành vi tài liệu chưa nói: mã trả về của từng endpoint, `422` khi sai mật khẩu cũ, giới hạn độ dài mật khẩu.
- [ ] `./gradlew spotlessCheck test compileTestJava` sạch; markdownlint sạch; ghi "Tiến độ"; commit.

## Tiến độ

Ghi ở đây trạng thái cuối và việc còn dở khi dừng giữa chừng.
