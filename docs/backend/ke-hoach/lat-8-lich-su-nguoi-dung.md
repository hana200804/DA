# Lát 8 · Lịch sử xử lý và quản lý người dùng

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

**Tạo:** `src/test/.../identity/AdminUsersIT.java`, `src/test/.../audit/AuditLogsIT.java`.

- [ ] Khoá người dùng thì phiên cũ của người đó nhận `401`; mở khoá rồi đăng nhập lại được.
- [ ] Đổi vai trò thì có dòng `USER/ROLE_CHANGED` với before/after đúng, `actorName` đúng; phiên cũ nhận `401`.
- [ ] `MODERATOR` gọi `/admin/users` nhận `403`; tự khoá nhận `422`.
- [ ] Lọc lịch sử theo khoảng thời gian (dòng ngoài khoảng không xuất hiện, hai đầu được tính); `from > to` trả `400`.
- [ ] Chỉ compile trên máy, chạy trên CI. Commit.

### Nhiệm vụ 5 · Tài liệu và kiểm tra cuối

**Sửa:** `docs/07-api.md` (chỉ mục lịch sử và người dùng), kế hoạch này.

- [ ] `docs/07 §8` ghi rõ mặc định phân trang, thứ tự sắp, hai đầu khoảng thời gian, mã `400`, `404`, `422`, `204`, thu hồi phiên.
- [ ] `./gradlew spotlessCheck test compileTestJava` sạch; markdownlint sạch; ghi "Tiến độ"; commit.

## Tiến độ

Ghi ở đây trạng thái cuối và việc còn dở khi dừng giữa chừng.
