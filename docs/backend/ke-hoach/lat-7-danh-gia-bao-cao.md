# Lát 7 · Đánh giá và báo cáo

Lát này triển khai FR-07, FR-09, FR-10, FR-22 và FR-32. Hợp đồng API ở [docs/07](../../07-api.md),
cách tính điểm ở [docs/04 §6](../../04-thiet-ke-ai.md#6-gom-cụm-và-điểm-tin-cậy).

## Ràng buộc chung

- Làm phần 1 trên nền lát 1–5, song song với lát 6. Mã nghiệp vụ ở `sign.internal.feedback`,
  endpoint ở `sign.web` và `sign.web.admin.reports`.
- Dùng JDBC, `Clock`, kho đối tượng và `AuditRecorder` hiện có. Migration dùng dải V70–V79.
- Quyền truy cập tuân theo hợp đồng của [lát 1](lat-1-tai-khoan.md#hợp-đồng-cho-lát-khác).

## Phần 1 · Đánh giá, gửi báo cáo và từ chối báo cáo

- [x] Khoá dòng biển khi đánh giá, cấm tự đánh giá biển đã đề xuất. Ghi đè theo người dùng,
  đếm lại DENY từ lần duyệt gần nhất và dùng `ClusteringEngine.applyRating` để tính điểm,
  nâng phiên bản và ghi sự kiện chuyển sang rà soát.
- [x] Trả `myRating` cho người đã đăng nhập trên chi tiết biển, giữ `Cache-Control: no-store`.
- [x] Nhận báo cáo JSON hoặc multipart. Kiểm tra lý do, mô tả và ảnh. Xử lý báo cáo chờ trùng
  bằng ràng buộc duy nhất và xoá ảnh khi giao dịch rollback.
- [x] Phân trang báo cáo cá nhân theo người gọi và hàng đợi quản trị theo biển. Tra tên người
  báo cáo qua `identity.api` và ký URL ảnh 10 phút.
- [x] Từ chối báo cáo đang chờ và ghi lịch sử cùng giao dịch. Dọn ảnh sau 90 ngày kể từ khi xử lý.
- [x] Bổ sung quy tắc URL, kiểm thử phân quyền, UT-B08 và IT-B11 cùng các ca API liên quan.
- [x] Chạy kiểm tra Gradle và Markdown.

## Phần 2 · Chấp nhận báo cáo sau khi ghép lát 6

- [ ] Dùng dịch vụ sửa và gỡ biển của lát 6 cho `POST /admin/reports/{id}/accept`.
- [ ] Nhận `{ action: EDIT, version, changes }` (cần ít nhất một thay đổi) hoặc
  `{ action: REMOVE, version }`. Phiên bản biển lệch trả `409 SIGN_ALREADY_PROCESSED`.
- [ ] Khoá và kiểm tra báo cáo đang chờ. Sửa hoặc gỡ biển, chuyển báo cáo sang `ACCEPTED`,
  lưu kết quả `EDITED` hoặc `REMOVED` và ghi lịch sử trong cùng giao dịch.
- [ ] Kiểm thử cả hai hành động, xung đột phiên bản, rollback và việc các báo cáo đang chờ khác
  của cùng biển vẫn giữ nguyên trạng thái.

## Tiến độ

### 2026-11-24

- Nền lát 1–5 đã có trên nhánh `backend/7-ratings-reports`. Phần 1 dùng lược đồ hiện có và thêm hai
  chỉ mục V70 cho phân trang cá nhân và tra khoá ảnh.
- Dùng mốc `updated_at >= reviewed_at` cho DENY theo luật lát 5. Nếu biển chưa có mốc duyệt, đếm
  tất cả DENY hiện hành. Đếm lại từ bảng trong lúc giữ khoá biển và để `applyRating` áp dụng
  chênh lệch của đánh giá đang ghi.
- Thêm `identity.api.UserDirectory.displayNames(Collection<String>)` để tra tên một lần cho cả
  trang. Dùng cùng cài đặt JDBC của lát 8. Khi ghép lát 8, dùng khai báo interface của lát 8
  để nối với `audit.api.ActorNames`.
- Job dọn ảnh chạy lúc 03:15 UTC. Ảnh hết hạn là ảnh của báo cáo đã xử lý đủ 90 ngày. Job cũng
  dọn ảnh mồ côi quá một giờ nếu việc xoá lúc rollback bị gián đoạn.
- 464 unit test đạt. Cả `spotlessApply test compileTestJava` và
  `spotlessCheck test compileTestJava jacocoTestCoverageVerification` đã qua. 7 ca trong
  `FeedbackIT` đã biên dịch và sẽ chạy trên CI với Docker.
