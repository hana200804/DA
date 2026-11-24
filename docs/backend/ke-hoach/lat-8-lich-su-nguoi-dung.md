# Lát 8 · Lịch sử xử lý và quản lý người dùng

Tài liệu ghi lại triển khai ban đầu của lát này. Cơ chế xác thực và thu hồi phiên
hiện hành được cập nhật ở [CSDL §10](../../06-co-so-du-lieu.md#10-redis).

> Kế hoạch thực thi theo [thiết kế triển khai](../thiet-ke-trien-khai.md) §6 hàng "8 · Lịch sử và người dùng". Đánh dấu `- [x]` khi xong từng bước để người khác (hoặc Codex) làm tiếp được.

**Mục tiêu:** kiểm duyệt viên xem lịch sử xử lý có lọc và phân trang; quản trị viên tìm người dùng, đổi vai trò, khoá và mở khoá; khoá hoặc đổi vai trò thì mọi phiên cũ của người đó mất hiệu lực.

**Yêu cầu:** FR-23 (lịch sử không sửa, không xoá qua API), FR-34, FR-36. **Ca nghiệm thu:** UT-B11 đầy đủ cho các endpoint của lát; khoá tài khoản thì phiên cũ nhận `401`.

**Công nghệ:** như [lát 1](lat-1-tai-khoan.md). Không thêm phụ thuộc, không thêm migration, không sửa `application.yml`.

## Phần 1 và phần 2

Nhánh đã chứa lát 0 và lát 1, nên mọi việc của lát này đều thuộc **phần 2** theo quy tắc làm song song (`docs/backend/quy-tac-song-song.md` ở nhánh cha `hana200804/Backend`): endpoint cần quyền dùng `@PreAuthorize`, thêm dòng vào `PermissionMatrixTest`, không sửa luật của test đó. Lát này chạy song song với lát 2, 9 nên chỉ sửa mục endpoint của mình ở `docs/07`, không đụng `shared/`, `build.gradle.kts`, `application.yml`.

## Hợp đồng cho lát khác

```java
package vn.signa.identity.api;

public interface UserDirectory extends vn.signa.audit.api.ActorNames {
  /** Tên hiển thị theo id; id không có thì không có trong map. Gọi một lần cho cả trang. */
  Map<String, String> displayNames(Collection<String> ids);
}
```

Lát 7 dùng `UserDirectory` cho `reporterName`. `audit` lấy tên qua cổng `audit.api.ActorNames` cùng chữ ký, vì `identity` đã phụ thuộc `audit.api` (ghi lịch sử) và `ModuleBoundaryTest` cấm chu trình giữa hai module. Bean cài đặt duy nhất nằm trong `identity.internal`.

## Ràng buộc chung

- `JdbcClient` và SQL viết tay. `audit` chỉ đọc `audit_logs`; `identity` chỉ đụng `users` (`TableOwnershipTest`).
- Không có endpoint sửa hoặc xoá lịch sử. Tài khoản `signa_app` vẫn chỉ có `SELECT, INSERT` trên `audit_logs`.
- Thời gian lấy từ bean `Clock`. Mã trả về và định dạng lỗi theo `docs/07 §2`.
- Máy không có Docker: `*IT` chỉ compile trên máy, chạy trên CI.

## Điểm cần soi khi review

1. `GET /admin/audit-logs` sắp `created_at DESC, id DESC`; `from`, `to` lấy cả hai đầu; `from > to`, `size > 100`, `page < 0`, `entityType` lạ đều trả `400`.
2. `beforeValue`, `afterValue` là JSON object, không phải chuỗi JSON. `actorName` lấy bằng **một** lần gọi `ActorNames` cho cả trang; `SYSTEM` thì `actorId`, `actorName` là `null`.
3. `q` được lowercase, escape ký tự đại diện của `LIKE` (`%`, `_` và ký tự escape `!`), nên `q=_` chỉ khớp email chứa dấu gạch dưới.
4. Tự hạ vai trò, tự khoá chính mình trả `422 BUSINESS_RULE`. Khoá hoặc hạ vai trò quản trị viên `ACTIVE` cuối cùng trả `422`. Hai quản trị viên khoá nhau cùng lúc không làm hệ thống mất quản trị viên: giao dịch khoá các dòng `ADMIN` `ACTIVE` bằng `FOR UPDATE` trước khi đọc dòng đích.
5. Đổi vai trò và khoá ghi `USER/ROLE_CHANGED`, `LOCKED`, `UNLOCKED` kèm before/after trong cùng giao dịch với `UPDATE users`. PATCH không đổi gì thì không ghi lịch sử.
6. Khoá hoặc đổi vai trò gọi `SessionRevoker.revokeAll` trong giao dịch (Redis lỗi thì rollback), rồi gọi lại sau commit để xoá phiên tạo ra trong lúc giao dịch chưa commit.

## Các nhiệm vụ

### Nhiệm vụ 0 · Kế hoạch

**Tạo:** `docs/backend/ke-hoach/lat-8-lich-su-nguoi-dung.md`.

- [x] Viết kế hoạch, markdownlint sạch, commit riêng.

### Nhiệm vụ 1 · Xem lịch sử `GET /admin/audit-logs`

**Tạo:** `audit/api/ActorNames.java`, `identity/api/UserDirectory.java`, `identity/internal/JdbcUserDirectory.java`, `audit/internal/AuditLogQuery.java` (bộ lọc đã kiểm tra), `AuditLogRepository.java`, `AuditLogService.java`, `AuditLogView.java`, `AuditPage.java`, `audit/web/AuditLogController.java`; test `AuditLogQueryTest`, `AuditLogServiceTest`, `AuditLogControllerTest`, `identity/web/SecuredWebMvc.java` (nạp Security thật của identity cho `@WebMvcTest` ở module khác, vì `SecurityConfig` không công khai).

- [x] Tham số `entityType` (enum), `entityId` ≤ 36, `actorId` ≤ 36, `action` ≤ 40, `from`, `to` (ISO-8601), `page` ≥ 0 (mặc định 0), `size` 1–100 (mặc định 20). `from > to` trả `400` với `details[].field = from`.
- [x] SQL `WHERE` ghép từ các điều kiện có mặt, `COUNT(*)` cùng điều kiện, `ORDER BY created_at DESC, id DESC LIMIT … OFFSET …`.
- [x] `before_value`, `after_value` đọc thành `JsonNode`. `actorName` lấy qua `ActorNames.displayNames` một lần cho các `actorId` khác `null` của trang.
- [x] `JdbcUserDirectory`: `SELECT id, display_name FROM users WHERE id IN (…)`; danh sách rỗng thì không truy vấn.
- [x] `@PreAuthorize("hasAuthority('audit:read')")`. Không có `POST`, `PUT`, `PATCH`, `DELETE`.
- [x] Unit test bộ lọc, service (ActorNames gọi một lần, JSON object); `@WebMvcTest` controller; thêm dòng `GET /admin/audit-logs` vào `PermissionMatrixTest` ngay ở nhiệm vụ này để ma trận luôn nạp được mọi controller. Commit.

### Nhiệm vụ 2 · Quản lý người dùng `GET`, `PATCH /admin/users`

**Tạo:** `identity/internal/LikePattern.java`, `UserAdminService.java`, `ManagedUser.java`, `UserSearch.java`, `UserPage.java`; sửa `UserRepository.java`; `identity/web/AdminUserController.java` (DTO `UpdateUser` lồng trong controller); test `LikePatternTest`, `UserAdminServiceTest`, `AdminUserControllerTest`.

- [x] `GET`: `q` ≤ 255, lowercase và escape `LIKE`; `role` thuộc `USER|MODERATOR|ADMIN`; `status` thuộc `ACTIVE|LOCKED`; phân trang như NV1, sắp `created_at DESC, id DESC`. Trả `{ items: [{ id, email, displayName, role, status, createdAt, lastLoginAt }], page, size, total }`.
- [x] `PATCH /{id}` `{ role?, status? }`: thiếu cả hai trả `400`; không có người dùng trả `404`; trả `204`.
- [x] Trong một giao dịch: `FOR UPDATE` các quản trị viên `ACTIVE`, rồi dòng đích; kiểm luật tự thay đổi và quản trị viên cuối cùng (`422`); `UPDATE users`; ghi `ROLE_CHANGED`, `LOCKED`, `UNLOCKED` kèm before/after; `revokeAll` khi khoá hoặc đổi vai trò. Sau commit gọi `revokeAll` lần nữa.
- [x] `@PreAuthorize("hasAuthority('user:manage')")`; người thực hiện lấy từ `@AuthenticationPrincipal AuthenticatedUser`.
- [x] Unit test escape `LIKE`, luật quản trị viên cuối cùng và tự thay đổi, ghi lịch sử, thu hồi phiên; `@WebMvcTest` controller. Commit.

### Nhiệm vụ 3 · Ma trận quyền UT-B11

**Sửa:** `src/test/.../identity/web/PermissionMatrixTest.java`.

- [x] Thêm `Endpoint.of` cho `GET /admin/audit-logs` (`audit:read`), `GET /admin/users` và `PATCH /admin/users/{id}` (`user:manage`); `@MockitoBean` cho `AuditLogService`, `UserAdminService`. Không sửa `violation(...)`.
- [x] Làm cùng commit với nhiệm vụ 1 (dòng lịch sử) và nhiệm vụ 2 (hai dòng người dùng), để mỗi commit đều nạp được mọi controller. `PATCH` dùng id `u-khac` để `ADMIN` không chạm luật tự khoá.

### Nhiệm vụ 4 · Test tích hợp

**Tạo:** `src/test/.../identity/AdminUsersIT.java`, `src/test/.../audit/AuditLogsIT.java`, `src/test/.../support/ApiClient.java` (đăng ký, đặt vai trò bằng `UPDATE users`, đăng nhập, gọi có token).

- [x] Khoá người dùng thì phiên cũ của người đó nhận `401`; mở khoá rồi đăng nhập lại được.
- [x] Đổi vai trò thì có dòng `USER/ROLE_CHANGED` với before/after đúng, `actorName` đúng; phiên cũ nhận `401`.
- [x] `MODERATOR` gọi `/admin/users` nhận `403`; tự khoá nhận `422`.
- [x] Lọc lịch sử theo khoảng thời gian (dòng ngoài khoảng không xuất hiện, hai đầu được tính); `from > to` trả `400`.
- [x] Tìm `q=_`, `q=%` chỉ trả email chứa đúng ký tự đó; `PATCH` id không có trả `404`.
- [x] Chỉ compile trên máy, chạy trên CI. Luật quản trị viên cuối cùng chỉ có unit test, vì CSDL dùng chung giữa các IT luôn có nhiều `ADMIN`. Commit.

### Nhiệm vụ 5 · Tài liệu và kiểm tra cuối

**Sửa:** `docs/07-api.md` (chỉ mục lịch sử và người dùng), kế hoạch này.

- [x] `docs/07 §8` ghi rõ mặc định phân trang, thứ tự sắp, hai đầu khoảng thời gian, mã `400`, `404`, `422`, `204`, thu hồi phiên.
- [x] `./gradlew spotlessCheck test compileTestJava` sạch; markdownlint sạch; ghi "Tiến độ"; commit.

## Tiến độ

Ghi ở đây trạng thái cuối và việc còn dở khi dừng giữa chừng.

**2026-11-24.** NV0–NV5 đã commit trên `backend/8-audit-users`, chưa push.

- Trên máy: `./gradlew clean spotlessCheck test compileTestJava` thành công, 214/214 unit test đạt, 216 sau việc bổ sung (thêm 51 so với lát 1: `AuditLogQueryTest` 6, `AuditLogServiceTest` 3, `AuditLogControllerTest` 6, `LikePatternTest` 4, `UserAdminServiceTest` 14, `AdminUserControllerTest` 6, `PermissionMatrixTest` từ 68 lên 80 ca với 3 endpoint mới). `markdownlint` sạch.
- Chỉ verify được trên CI (máy không có Docker): `AdminUsersIT` (khoá thì phiên cũ `401`, mở khoá đăng nhập lại được, đổi vai trò có lịch sử đúng before/after và `requestId`, `MODERATOR` nhận `403`, tự khoá `422`, escape `LIKE`, `404`), `AuditLogsIT` (lọc theo khoảng thời gian lấy hai đầu, theo người thực hiện và hành động, phân trang, `from > to` trả `400`, `USER` nhận `403`).
- Quyết định tự đưa ra:
  - `audit` không gọi thẳng `identity.api.UserDirectory` như đề bài, vì `identity` đã phụ thuộc `audit.api` và `ModuleBoundaryTest` cấm chu trình giữa module (quy tắc song song cấm sửa luật). Cổng `audit.api.ActorNames` có cùng chữ ký; `identity.api.UserDirectory` mở rộng cổng này; bean `identity.internal.JdbcUserDirectory` cài đặt cả hai.
  - `PATCH /admin/users/{id}` trả `204`, kể cả khi không có gì thay đổi (khi đó không ghi lịch sử, không thu hồi phiên). Đổi cả vai trò và trạng thái ghi hai dòng lịch sử. Mở khoá không thu hồi phiên.
  - "Quản trị viên" trong luật cuối cùng là `role_code = 'ADMIN'`, giống `BootstrapAdmin`. Mọi PATCH khoá tập quản trị viên `ACTIVE` bằng `FOR UPDATE` trước dòng đích, nên hai quản trị viên khoá nhau cùng lúc chạy lần lượt và không gây deadlock.
  - Tự đổi vai trò sang giá trị khác bị chặn, không chỉ tự hạ, vì `ADMIN` đã là vai trò cao nhất.
  - Thu hồi phiên hai lần: trong giao dịch (Redis lỗi thì rollback, trả `500`) và sau commit (lỗi chỉ ghi log), để xoá phiên đăng nhập kịp tạo khi trạng thái mới chưa commit.
  - `LIKE` dùng `ESCAPE '!'` thay vì `\`, để không phụ thuộc `sql_mode`.
  - Mặc định `size = 20`. Danh sách người dùng sắp theo `created_at DESC, id DESC`, giống lịch sử.
  - Dòng `PermissionMatrixTest` thêm ngay ở commit có controller (NV1, NV2), vì `@WebMvcTest` nạp mọi controller nên thiếu `@MockitoBean` là ma trận không chạy được.
  - Test `@WebMvcTest` ở module khác cần Security thật của identity, mà `SecurityConfig` không công khai. Thêm file test mới `identity/web/SecuredWebMvc.java` thay vì sửa file của lát 1.
- Theo quy tắc làm song song: không sửa `shared/`, `build.gradle.kts`, `application.yml`, không thêm migration. `docs/07` chỉ sửa hai mục của lát 8. `UserRepository` có thêm phương thức; nếu lát khác cũng sửa file này thì có thể xung đột khi ghép.
- Rủi ro:
  - Đã xử lý (việc bổ sung): `@PreAuthorize` chạy sau khi Spring đọc và kiểm dữ liệu, nên người thiếu quyền gửi dữ liệu sai từng nhận `400` thay vì `403`. `SecurityConfig` thêm quy tắc theo URL `/api/v1/admin/users/**` → `user:manage` và `/api/v1/admin/audit-logs/**` → `audit:read`; `@PreAuthorize` giữ làm lớp thứ hai. Test `missingPermissionIs403EvenWithInvalid*` (`USER`, `MODERATOR` gửi thân hoặc tham số sai nhận `403`) fail khi bỏ hai dòng này. Bảng tiền tố `/admin/**` → quyền cho các lát sau nằm ở [lát 1](lat-1-tai-khoan.md), mục "Hợp đồng cho lát khác".
  - Thu hồi sau commit lỗi thì phiên tạo trong khoảng giao dịch đang chạy có thể còn sống. Khoảng này chỉ vài mili giây và có log lỗi.
  - `lockActiveAdmins` khoá cả khoảng chỉ mục `role_code = 'ADMIN'`, nên việc tạo hoặc nâng quyền quản trị viên đồng thời phải chờ. Thao tác này hiếm nên chấp nhận được.
  - Rủi ro "tài khoản bị khoá vẫn giữ phiên cũ" của lát 1 đã xử lý ở lát này.

**2026-11-24, ghép lát 2–7.** Đã merge `backend/7-ratings-reports` vào `backend/8-audit-users` bằng `--no-ff`, rồi thêm quy tắc mặc định cho `/admin`. Chưa push.

- Xung đột đã giải, giữ cả hai bên:
  - `identity/api/UserDirectory.java` (add/add): lát 7 tạo interface cùng tên và chép `JdbcUserDirectory` giống hệt lát 8. Giữ bản lát 8 (`UserDirectory extends audit.api.ActorNames`), để một bean phục vụ cả `AuditLogService` (qua `ActorNames`) và `ReportService` của lát 7 (qua `UserDirectory`). Nơi gọi không phải sửa.
  - `identity/web/SecurityConfig.java`: giữ các dòng của lát 5–7 (`submissions`, `admin/review-queue`, `admin/signs`, `admin/reports`, `rating`, `reports`, `me/reports`), rồi tới hai dòng của lát 8.
  - `PermissionMatrixTest`: gộp import, `@MockitoBean`, stub của cả hai bên. Các dòng `Endpoint.reserved` công khai mà lát 2–4 đã đổi sang `Endpoint.of` thì theo bản lát 7, rồi thêm ba dòng lát 8.
  - `docs/06`, `docs/07`, `build.gradle.kts` tự gộp, không có xung đột.
- Quy tắc mặc định: `/api/v1/admin/**` còn lại cần `review:decide`, đặt sau mọi dòng `/admin` cụ thể. Chỉ thêm dòng này thì 6 ca `CatalogAdminControllerTest` nhận `403`, vì vai trò giả chỉ có `catalog:manage`. Vì vậy thêm dòng `catalog:manage` cho `/admin/sign-types/**`, `/admin/sign-categories/**` trước dòng mặc định. Ca mới `catalogManageAloneIsEnoughAndReviewDecideAloneIsNot` chứng minh chỉ cần `catalog:manage`, còn chỉ có `review:decide` thì nhận `403`. `PermissionMatrixTest`: `reserved GET /admin/signs` đổi sang `review:decide`; thêm `reserved` cho `/admin/chua-co`, `/admin/stats`, `/admin/recognition/compare`.
- `./gradlew spotlessApply test compileTestJava jacocoTestCoverageVerification` thành công, 649/649 unit test (636 sau merge). `markdownlint` sạch. Các `*IT` của lát 2–8 vẫn chỉ chạy trên CI.
