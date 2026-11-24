# Lát 9 · Hạng mục P1

> Kế hoạch theo [thiết kế triển khai](../thiet-ke-trien-khai.md) §5.3 và §6. Đánh dấu từng bước khi hoàn thành.

**Mục tiêu:** hoàn thành quên mật khẩu bằng OTP qua email (FR-13, NFR-10) trong phần A. Phần B chờ các lát cung cấp dữ liệu và hợp đồng cần thiết.

## Ràng buộc chung

- Nhánh `backend/9-p1` đã có lát 0 và lát 1, chạy song song với lát 2 và lát 8.
- Triển khai phần A trong `identity`, dùng lại chính sách mật khẩu, BCrypt cost 12 và `SessionRevoker`. Không cần migration.
- Thêm phụ thuộc ở cuối khối `dependencies` kèm `// lát 9`. Cấu hình dưới `signa.identity.otp`. Thêm dòng vào Security và ma trận quyền theo hợp đồng lát 1.
- Chạy kiểm thử không cần Docker trên máy. Các lớp `*IT` chỉ biên dịch tại máy và chạy trên CI.

## Các nhiệm vụ

### Nhiệm vụ 0 · Kế hoạch

- [x] Viết kế hoạch và commit riêng trước khi triển khai.

### Phần A · Quên mật khẩu qua email

- [ ] Thêm API công khai `POST /api/v1/auth/password/forgot`. Phản hồi `202` rỗng cho mọi email hợp lệ trong hạn mức. Áp dụng giới hạn 3 yêu cầu trong 15 phút theo cặp email chuẩn hoá và IP trước khi tra tài khoản, trả `429` kèm `Retry-After` khi vượt.
- [ ] Chỉ sinh OTP 6 chữ số cho tài khoản `ACTIVE`, băm HMAC-SHA256 và lưu Redis 10 phút với bộ đếm từ 0; gửi lại thay mã cũ. Gửi mail bất đồng bộ, không ghi OTP ra log.
- [ ] Thêm API công khai `POST /api/v1/auth/password/reset`. Dùng so sánh thời gian hằng, xoá mã sau lần sai thứ 5 và trả cùng lỗi `422` cho mã sai, hết hạn hoặc đã bị xoá. Khi hợp lệ, cập nhật mật khẩu, xoá mã, thu hồi mọi phiên và trả `204`.
- [ ] Cấu hình bí mật OTP, SMTP, Mailpit và cập nhật tài liệu API, Redis, hướng dẫn chạy.
- [ ] Kiểm thử OTP, giới hạn, tài khoản không tồn tại, API và ma trận quyền. Biên dịch IT cho luồng đầy đủ, phiên cũ, giới hạn gửi và TTL.
- [ ] Chạy Gradle, markdownlint và commit phần A.

### Phần B · Các hạng mục làm sau

- [ ] FR-11 `GET /me/submissions` sau lát 5.
- [ ] FR-25 tự duyệt sau lát 5.
- [ ] FR-37 so sánh mô hình sau lát 3.
- [ ] FR-38 thống kê sau lát 5 và lát 6.
- [ ] FR-14 định nghĩa interface tìm địa điểm.

## Tiến độ

**2026-11-24.** Đã lập kế hoạch. Phần A sẵn sàng triển khai trên nền lát 0 và lát 1.
