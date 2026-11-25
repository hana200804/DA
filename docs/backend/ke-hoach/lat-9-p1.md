# Lát 9 · Hạng mục P1

> Kế hoạch theo [thiết kế triển khai](../thiet-ke-trien-khai.md) §5.3 và §6. Đánh dấu từng bước khi hoàn thành.

**Mục tiêu** gồm quên mật khẩu bằng OTP qua email (FR-13, NFR-10) trong phần A và các hạng mục P1 còn lại trong phần B.

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

### Phần B · Các hạng mục P1 còn lại

- [x] Ghép lát 2–7 vào `backend/9-p1` và kiểm tra Gradle trước khi triển khai. FR-11 `GET /me/submissions` đã có từ lát 5.
- [x] FR-37 so sánh ba mô hình, quy đổi box theo ảnh gốc, trả lỗi riêng theo mô hình và giới hạn 10 lượt/phút mỗi tài khoản.
- [x] FR-38 thống kê ngày UTC, quyết định, thời gian xử lý, nhóm biển và mật độ zoom 12. Thêm chỉ mục theo thời gian bằng migration `V90`.
- [x] FR-25 xác nhận worker đọc cấu hình tự duyệt mặc định tắt, cập nhật trạng thái thành viên và ghi lịch sử hệ thống. Thêm IT cho UT-B09 và UT-B10.
- [x] FR-14 hoàn thành khung `PlaceSearch` và API công khai với `NoopPlaceSearch`, kiểm tra `q` và giới hạn 30 lượt/phút mỗi IP. Bản cài đặt có kết quả thực tế sẽ được bổ sung sau khi chốt Q6.
- [x] Thêm ba quy tắc URL vào Security và ba endpoint vào ma trận quyền. Cập nhật hợp đồng tại [API §6](../../07-api.md#6-biển-đề-xuất-đánh-giá-báo-cáo) và [API §8](../../07-api.md#8-quản-trị).

## Tiến độ

**2026-11-24.** Đã lập kế hoạch. Phần A sẵn sàng triển khai trên nền lát 0 và lát 1.

**2026-11-24.** Đã triển khai phần A trên `backend/9-p1`. Gradle chạy thành công, 192/192 ca unit, MVC và kiến trúc đạt, tăng 29 ca gồm 8 ca ma trận quyền. Markdownlint sạch.

- `PasswordResetIT` có 5 ca đã biên dịch: đổi mật khẩu và thu hồi phiên, giới hạn gửi với email có hoặc không có tài khoản, TTL Redis, gửi lại và xoá ở lần sai thứ 5, hai yêu cầu dùng cùng mã. Máy làm việc thiếu Docker nên các ca này chạy trên CI. Compose đã parse YAML và xác nhận tag Mailpit có trên Docker Hub. Chưa chạy Compose.
- Redis cập nhật TTL và bộ đếm nguyên tử. Hai lần gửi liên tiếp không cấp lại mã còn hiệu lực. BCrypt giữ cost 12. Đăng nhập kiểm tra lại hash mật khẩu đồng thời khoá hàng người dùng khi cấp phiên, để yêu cầu đang xác thực bằng mật khẩu cũ không cấp được phiên sau khi reset. Đổi mật khẩu theo FR-01 cũng kiểm tra lại hash trước khi ghi.
- Bí mật OTP bắt buộc khi chạy ứng dụng web ngoài profile `test`. Profile `test` tự sinh bí mật trong bộ nhớ khi thiếu. SMTP local dùng Mailpit `v1.34.0`. Test khởi tạo context `migrate` với Flyway tắt xác nhận chế độ này chạy được khi thiếu `SIGNA_OTP_SECRET`.
- Điều phối viên đã chấp nhận hai rủi ro. Hàng đợi email trong bộ nhớ có thể mất yêu cầu đã nhận `202` khi đầy, backend khởi động lại hoặc SMTP lỗi. Người dùng có thể xin mã lại. Nếu thu hồi phiên gặp lỗi, giao dịch MySQL rollback và người dùng cần yêu cầu OTP mới.
- Phần B giữ lại trong kế hoạch để thực hiện sau khi các lát phụ thuộc sẵn sàng.

**2026-11-24 · Phần B.** Đã ghép lát 2–7 và triển khai các nhiệm vụ đã đánh dấu trên `backend/9-p1`.

- `./gradlew spotlessCheck test compileTestJava jacocoTestCoverageVerification` đạt. Có 641 ca unit, MVC và kiến trúc đạt, tăng 29 ca so với sau khi ghép. Trong đó có 12 ca mới của ma trận quyền. Markdownlint đạt.
- So sánh dùng chung bước kiểm tra và chuẩn bị ảnh với nhận diện. Kết quả có thứ tự cố định và mỗi mô hình có thể lỗi riêng. Hai bộ đếm mới dùng cửa sổ 60 giây trong Redis, tách khỏi giới hạn nhận diện thường. Khi Redis lỗi, API vẫn phục vụ và ghi cảnh báo.
- Thống kê lấy dữ liệu trong cùng một giao dịch chỉ đọc. Khoảng quyết định tính cả hai đầu mút. Các biển duyệt tay chưa có đề xuất vẫn được tính vào số quyết định và bỏ qua khi tính thời gian xử lý. Nhóm và mật độ phản ánh toàn bộ biển đang `APPROVED`.
- Worker tự duyệt hiện có đã đáp ứng FR-25. Hai ca mới truyền đề xuất qua Redis Stream vào worker thật và kiểm tra trạng thái biển, trạng thái thành viên và lịch sử trong MySQL. Mỗi ca dùng một Redis DB riêng để cách ly luồng trong CI.
- Bốn ca tích hợp cần Docker trên CI. Chúng gồm so sánh với DETR giả trả `503` trong `RecognitionIT`, thống kê với dữ liệu chèn sẵn trong `StatisticsIT`, cùng `AutoApprovalEnabledIT` và `AutoApprovalDisabledIT`. Máy làm việc đã biên dịch các ca này.
- Điều phối viên đã chấp nhận các rủi ro phần B. Thời gian chờ có thể cộng dồn qua ba lượt gọi mô hình tuần tự, truy vấn mật độ duyệt toàn bộ biển `APPROVED`, và API tiếp tục phục vụ khi Redis gián đoạn nên giới hạn tần suất tạm thời mất hiệu lực. Tìm địa điểm tiếp tục trả danh sách rỗng đến khi có nhà cung cấp sau Q6.

**2026-11-24 · Ghép lát 8.** Các quy tắc `stats:read` và `recognition:compare` đặt trước quy tắc mặc định `/api/v1/admin/**`. Ca hồi quy MVC xác nhận `MODERATOR` chỉ được cấp `stats:read` nhận `200` ở `GET /api/v1/admin/stats`, còn `USER` nhận `403`.
