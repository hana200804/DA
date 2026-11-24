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

- [x] Thêm API công khai `POST /api/v1/auth/password/forgot`. Phản hồi `202` rỗng cho mọi email hợp lệ trong hạn mức. Áp dụng giới hạn 3 yêu cầu trong 15 phút theo cặp email chuẩn hoá và IP trước khi tra tài khoản, trả `429` kèm `Retry-After` khi vượt.
- [x] Chỉ sinh OTP 6 chữ số cho tài khoản `ACTIVE`, băm HMAC-SHA256 và lưu Redis 10 phút với bộ đếm từ 0; gửi lại thay mã cũ. Gửi mail bất đồng bộ, không ghi OTP ra log.
- [x] Thêm API công khai `POST /api/v1/auth/password/reset`. Dùng so sánh thời gian hằng, xoá mã sau lần sai thứ 5 và trả cùng lỗi `422` cho mã sai, hết hạn hoặc đã bị xoá. Khi hợp lệ, cập nhật mật khẩu, xoá mã, thu hồi mọi phiên và trả `204`.
- [x] Cấu hình bí mật OTP, SMTP, Mailpit và cập nhật tài liệu API, Redis, hướng dẫn chạy.
- [x] Kiểm thử OTP, giới hạn, tài khoản không tồn tại, API và ma trận quyền. Biên dịch IT cho luồng đầy đủ, phiên cũ, giới hạn gửi và TTL.
- [x] Chạy Gradle, markdownlint và commit phần A.

### Phần B · Các hạng mục làm sau

- [ ] FR-11 `GET /me/submissions` sau lát 5.
- [ ] FR-25 tự duyệt sau lát 5.
- [ ] FR-37 so sánh mô hình sau lát 3.
- [ ] FR-38 thống kê sau lát 5 và lát 6.
- [ ] FR-14 định nghĩa interface tìm địa điểm.

## Tiến độ

**2026-11-24.** Đã lập kế hoạch. Phần A sẵn sàng triển khai trên nền lát 0 và lát 1.

**2026-11-24.** Đã triển khai phần A trên `backend/9-p1`. Gradle chạy thành công, 192/192 ca unit, MVC và kiến trúc đạt, tăng 29 ca gồm 8 ca ma trận quyền. Markdownlint sạch.

- `PasswordResetIT` có 5 ca đã biên dịch: đổi mật khẩu và thu hồi phiên, giới hạn gửi với email có hoặc không có tài khoản, TTL Redis, gửi lại và xoá ở lần sai thứ 5, hai yêu cầu dùng cùng mã. Máy làm việc thiếu Docker nên các ca này chạy trên CI. Compose đã parse YAML và xác nhận tag Mailpit có trên Docker Hub. Chưa chạy Compose.
- Redis cập nhật TTL và bộ đếm nguyên tử. Hai lần gửi liên tiếp không cấp lại mã còn hiệu lực. BCrypt giữ cost 12. Đăng nhập kiểm tra lại hash mật khẩu đồng thời khoá hàng người dùng khi cấp phiên, để yêu cầu đang xác thực bằng mật khẩu cũ không cấp được phiên sau khi reset. Đổi mật khẩu theo FR-01 cũng kiểm tra lại hash trước khi ghi.
- Bí mật OTP bắt buộc khi chạy ứng dụng web ngoài profile `test`. Profile `test` tự sinh bí mật trong bộ nhớ khi thiếu. SMTP local dùng Mailpit `v1.34.0`. Test khởi tạo context `migrate` với Flyway tắt xác nhận chế độ này chạy được khi thiếu `SIGNA_OTP_SECRET`.
- Điều phối viên đã chấp nhận hai rủi ro. Hàng đợi email trong bộ nhớ có thể mất yêu cầu đã nhận `202` khi đầy, backend khởi động lại hoặc SMTP lỗi. Người dùng có thể xin mã lại. Nếu thu hồi phiên gặp lỗi, giao dịch MySQL rollback và người dùng cần yêu cầu OTP mới.
- Phần B giữ lại trong kế hoạch để thực hiện sau khi các lát phụ thuộc sẵn sàng.
