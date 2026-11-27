# Tài liệu dự án Signa

Toàn bộ tài liệu thiết kế của dự án. Tài liệu được viết trước khi code và cập nhật trong cùng Pull Request khi thiết kế thay đổi.

## Danh mục

| # | Tài liệu | Nội dung |
| --- | --- | --- |
| 01 | [Tổng quan](01-tong-quan.md) | Đề tài, bài toán, đóng góp, tác nhân, phạm vi, phân công |
| 02 | [Đặc tả yêu cầu](02-dac-ta-yeu-cau.md) | Mục tiêu, phân quyền, user story, yêu cầu chức năng, mô hình, phi chức năng, tiêu chí chấp nhận, phân kỳ |
| 03 | [Kiến trúc hệ thống](03-kien-truc.md) | Thành phần, luồng xử lý, ước lượng tải, triển khai, đánh đổi |
| 04 | [Thiết kế AI](04-thiet-ke-ai.md) | Ba mô hình, giao thức huấn luyện và đánh giá, nhận diện trên điện thoại, gom cụm và điểm tin cậy, dịch vụ nhận diện |
| 05 | [Dữ liệu và gán nhãn](05-du-lieu.md) | Nguồn dữ liệu, danh mục nhãn, hướng dẫn gán nhãn, phân chia, phiên bản |
| 06 | [Cơ sở dữ liệu](06-co-so-du-lieu.md) | Lược đồ theo các nhóm dữ liệu của đề tài, truy vấn không gian, Redis, MinIO |
| 07 | [API](07-api.md) | Quy ước, REST, dịch vụ nhận diện nội bộ, định dạng lỗi |
| 08 | [Kiểm thử](08-kiem-thu.md) | Chiến lược, so sánh mô hình, trên thiết bị, thực địa, đánh giá phê duyệt |
| 09 | [Hạng mục công việc](09-hang-muc-cong-viec.md) | Kế hoạch 16 tuần theo thành viên, milestone, rủi ro tiến độ |
| — | [ADR](adr/) | Các quyết định kiến trúc và lý do |

## Bàn giao triển khai

- [Ngữ cảnh Android từ dự án cũ](ban-giao-android-tu-du-an-cu.md): tiến độ lịch sử, khác biệt bản đã chuyển và điểm nối backend/AI.

## Quy ước

- Viết bằng tiếng Việt; thuật ngữ kỹ thuật giữ tiếng Anh khi không có từ thông dụng.
- Sơ đồ dùng [Mermaid](https://mermaid.js.org/) để GitHub hiển thị trực tiếp và sửa được bằng văn bản.
- Mã yêu cầu (`FR-xx`, `NFR-xx`, `AI-xx`) chỉ được định nghĩa trong [Đặc tả yêu cầu](02-dac-ta-yeu-cau.md). Tài liệu khác tham chiếu mã này.
- Thay đổi một quyết định đã chốt: viết ADR mới, ADR cũ chuyển trạng thái *Superseded*.

## Thuật ngữ

| Thuật ngữ | Nghĩa |
| --- | --- |
| QCVN 41 | QCVN 41:2024/BGTVT — Quy chuẩn kỹ thuật quốc gia về báo hiệu đường bộ, ban hành kèm Thông tư 51/2024/TT-BGTVT |
| Nhóm biển | Cấm, hiệu lệnh, nguy hiểm và cảnh báo, biển phụ, khác — dùng để hiển thị, lọc, gom cụm |
| Mã biển | Mã theo QCVN 41, ví dụ `P.123a`; biển tốc độ có hậu tố giá trị, ví dụ `P.127_50`; mỗi mã là một lớp của mô hình |
| Danh mục nhãn | Danh sách lớp mà mô hình nhận diện, có số phiên bản, nguồn duy nhất là `ai/data/labels.yaml` |
| Đề xuất | Một lần người dùng gửi "có biển mã X tại vị trí Y", kèm ảnh cắt và độ tin cậy |
| Biển | Một biển báo thật trên đường, gom từ nhiều đề xuất, qua phê duyệt mới hiển thị |
| Điểm tin cậy | Mức tin rằng biển có thật và đúng mã, tính từ đề xuất, đánh giá và quyết định của kiểm duyệt viên |
| Gợi ý | Nên duyệt / Cần xem kỹ / Chờ thêm — hệ thống đề xuất cho kiểm duyệt viên |
| Đánh giá | Người dùng xác nhận biển "còn đúng" hoặc "không đúng" |
| Track | Chuỗi phát hiện của cùng một biển qua nhiều khung hình liên tiếp |
| On-device | Chạy trên điện thoại, không cần server |
