# Quy tắc làm song song các lát backend

Các lát 1, 2, 3, 4, 5 cùng tách từ `backend/0-scaffold` và chạy song song. Sau đó chúng được ghép lại theo thứ tự 1 → 2 → 3 → 4 → 5. Quy tắc dưới đây giữ cho lúc ghép ít xung đột nhất.

## Phạm vi theo đợt

- Đợt song song chỉ làm phần **không cần đăng nhập**, hoặc phần logic thuần.
- Endpoint cần quyền làm ở "phần 2" của lát, sau khi nhánh đã merge `backend/1-identity`. Chỉ lát 1 được thêm Spring Security.
- Mỗi lát ghi rõ trong kế hoạch của mình việc nào thuộc phần 1, việc nào thuộc phần 2.

## File dùng chung

| File | Quy tắc |
| --- | --- |
| `backend/build.gradle.kts` | Chỉ thêm dòng phụ thuộc vào **cuối** khối `dependencies`, kèm chú thích `// lát N`. Không sửa dòng có sẵn |
| `backend/src/main/resources/application.yml` | Chỉ thêm một khối `signa.<module>:` ở cuối file. Mỗi module tự định nghĩa `@ConfigurationProperties("signa.<module>")` trong gói `internal` của mình. Không sửa `SignaProperties` |
| Migration Flyway | Lát N chỉ dùng số `V{N}0__…` tới `V{N}9__…`, ví dụ lát 2 dùng `V20__`. Bảng mới phải có `GRANT` cho `${app_user}` trong cùng migration |
| `docs/07-api.md` | Chỉ sửa mục của endpoint thuộc lát mình |
| `shared/` | Không sửa. Nếu thật sự cần, ghi lý do vào mục "Tiến độ" và báo người điều phối |
| Test kiến trúc, `PermissionMatrixTest` | Không sửa luật. Lát 1 sở hữu `PermissionMatrixTest`, các lát khác thêm dòng ở phần 2 |

## Kỹ thuật chung

- Máy không có Docker: test `*IT` chỉ compile được, sẽ chạy trên CI. Unit test phải chạy được không cần Docker.
- Dùng `JdbcClient` cùng SQL viết tay, không dùng JPA. Module chỉ viết SQL trên bảng của mình (`TableOwnershipTest`). Module khác chỉ được gọi qua gói `api`.
- Thời gian lấy từ bean `Clock`. Toạ độ dùng `ST_GeomFromText(…, 4326, 'axis-order=long-lat')` theo `docs/06 §9`.
- Kế hoạch lát nằm ở `docs/backend/ke-hoach/lat-N-<tên>.md`, cùng khuôn với `lat-0-khung.md`, có mục "Tiến độ".
- Mỗi nhiệm vụ một commit, message tiếng Việt. Trước commit chạy `cd backend && ./gradlew spotlessApply test compileTestJava`, lệnh phải thành công. Markdown phải qua `npx --yes markdownlint-cli2@0.18.1`.
- Không commit `.inbox/`. Không push, không tạo PR, không sửa nhánh khác.
