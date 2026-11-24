# Theo dõi review backend Signa

Nguồn gồm hai báo cáo [lát 0–4](review-lat-0-4.md) và [lát 5–8](review-lat-5-8.md).
Mã A và B đi theo thứ tự phát hiện trong từng báo cáo.

| Mã | Phát hiện | Mức độ | Người sửa | Commit | Trạng thái |
| --- | --- | --- | --- | --- | --- |
| A1 | Mật khẩu cũ có thể tạo phiên còn hiệu lực sau khi đổi mật khẩu | S1 | fix1 | `62912f6` | Đã sửa |
| A2 | Tải ảnh minh hoạ cùng định dạng ghi đè ảnh cũ kể cả khi giao dịch rollback | S1 | fix2 | — | Chờ xử lý |
| A3 | Khung nhìn chạm kinh độ −180° có thể trả HTTP 500 | S2 | fix2 | — | Chờ xử lý |
| A4 | Khởi tạo MySQL có thể hỏng với mật khẩu hợp lệ hoặc tên tài khoản đã cấu hình | S2 | fix1 | `ce7295a` | Đã sửa |
| A5 | Backend trong Compose không nhận địa chỉ dịch vụ nhận diện | S2 | fix1 | `1bda8fa` | Đã có ở phần A và được giữ khi ghép. Compose `app`, `dev`, `demo` đều trỏ đến `http://recognition:8000`. |
| A6 | Giới hạn ảnh theo request không giới hạn tổng bộ nhớ giải mã đồng thời | S2 | fix2 | — | Chờ xử lý |
| A7 | Một tài khoản MySQL có thể bị tính vào nhiều bộ đếm đăng nhập | S2 | fix1 | `1f38988` | Đã sửa |
| A8 | SchemaIT đợi migration 3 khi lát 2 đã thêm V20 | S2 | fix1 | `4c8dbbe` | Đã sửa |
| A9 | Chạy migration thường lại trên CSDL đã nạp demo bị lỗi Flyway validate | S3 | fix1 | `99fa887` | Đã sửa bằng `repeatable:missing` |
| A10 | Lượt đọc đang chạy có thể ghi lại cache danh mục cũ sau khi quản trị viên commit | S3 | fix2 | — | Chờ xử lý |
| A11 | Nạp nhãn có thể ghi đè tên vừa sửa và ghi sai giá trị trước thay đổi | S3 | fix2 | — | Chờ xử lý |
| A12 | Đổi nhóm một loại biển để lại nhóm cũ trên bản đồ | S3 | fix2 | — | Chờ xử lý |
| A13 | API tạo được mã loại không thể dùng trong API chi tiết, sửa hoặc lọc | S3 | fix2 | — | Chờ xử lý |
| A14 | Tệp chỉ có chữ ký ảnh vẫn được lưu công khai như ảnh hợp lệ | S3 | fix2 | — | Chờ xử lý |
| A15 | Thu nhỏ có thể làm ảnh hợp lệ ở đầu vào thành ảnh bị dịch vụ AI từ chối | S3 | fix2 | — | Chờ xử lý |
| B1 | Đăng nhập đồng thời có thể cấp lại phiên với quyền đã bị thu hồi | S1 | fix1 | `62912f6` | Đã sửa |
| B2 | Quét phục hồi nhân bản hàng đợi khi tồn nhiều đề xuất | S2 | fix2 | — | Chờ xử lý |
| B3 | MinIO chậm giữ khoá biển và chiếm kết nối MySQL khi gửi báo cáo | S2 | fix2 | — | Chờ xử lý |
| B4 | Đổi nhóm loại biển làm worker bỏ qua biển cùng mã đã công bố | S2 | fix2 | — | Chờ xử lý |
| B5 | Chốt mã khác vẫn công bố ảnh đại diện của mã cũ | S3 | fix2 | — | Chờ xử lý |
| B6 | Trung vị kinh độ có thể kéo biển qua nửa vòng Trái Đất | S3 | fix2 | — | Chờ xử lý |
| B7 | Dữ liệu chỉ có chữ ký tệp được lưu và trả như ảnh | S3 | fix2 | — | Chờ xử lý |
| B8 | Lịch sử rà soát do đánh giá ghi sai điểm trước thay đổi | S3 | fix2 | — | Chờ xử lý |
| B9 | Chi tiết và phân trang phê duyệt có thể ghép các phiên bản dữ liệu | S3 | fix2 | — | Chờ xử lý |
| B10 | MySQL rollback nhưng một phần phiên đã bị xoá khỏi Redis | S3 | fix1 | `62912f6` | Đã sửa |

## Ghi chú CI

| Kiểm thử | Người sửa | Commit | Trạng thái |
| --- | --- | --- | --- |
| `SchemaIT` (cùng A8) | fix1 | `4c8dbbe` | Đã sửa |
| `BootstrapAdminIT` | fix1 | `67f2118` | MySQL riêng |

A9 có ca theo cấu hình Flyway thật trong `DemoMigrationIT`. Ca `DemoDataIT`
ở catalog vẫn chạy SQL trực tiếp để kiểm dữ liệu mẫu và nạp nhãn.

A4 dùng `NO_BACKSLASH_ESCAPES` và nhân đôi dấu nháy đơn trong mật khẩu.
Test shell kiểm nguyên văn SQL tạo ra với tên tuỳ chỉnh và mật khẩu có
ký tự đặc biệt. [MySQL 8.4, String Literals](https://dev.mysql.com/doc/refman/8.4/en/string-literals.html).
