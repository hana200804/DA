# Lát 10b · Sửa review backend

## Phạm vi

Sửa các phát hiện được giao trên nhánh `backend/10b-review-fixes`, từ nền lát 9. Mỗi commit ghi mã phát hiện hoặc nhóm cùng nguyên nhân.

## Thực hiện

| Mã | Thay đổi và kiểm tra |
| --- | --- |
| A2 | Tạo khóa ảnh minh hoạ theo phiên bản và dọn đối tượng theo kết quả giao dịch |
| A10, A11, A13 | Chặn cache cũ được công bố sau thay đổi, khoá dòng khi nạp nhãn, thống nhất quy tắc mã loại |
| A12, B4 | Đồng bộ nhóm lưu trên biển trong cùng giao dịch đổi danh mục, kiểm tra gom cụm và bộ lọc bản đồ |
| A3 | Xử lý khung nhìn toàn cầu và phía tây kinh tuyến đổi ngày |
| A6, A15 | Giới hạn raster giải mã đồng thời và giữ kích thước hợp lệ khi thu nhỏ |
| A14, B7 | Giới hạn chiều và pixel, xác thực cấu trúc và giải mã icon, crop và ảnh báo cáo |
| B2, B5 | Đưa đề xuất vào stream nguyên tử theo ID, quét hữu hạn và tính trung vị kinh độ trên vòng tròn |
| B3, B8 | Đưa upload báo cáo ra ngoài giao dịch và ghi điểm trước đánh giá từ dòng đã khoá |
| B6, B9 | Chọn lại ảnh đại diện khi đổi mã, đọc hàng đợi và chi tiết từ một snapshot |

Chạy `spotlessCheck test compileTestJava jacocoTestCoverageVerification` bằng Gradle wrapper và Markdown lint theo README. Ca Testcontainers chạy trên CI khi máy phát triển thiếu Docker.

## Tiến độ

- Đã xác nhận nhánh và tổ tiên lát 9, đọc hai báo cáo và đối chiếu thiết kế các lát liên quan.
- A12/B4 giữ bản sao nhóm trên biển để bản đồ và gom cụm tiếp tục lọc theo chỉ mục hiện có. Danh mục phát sự kiện đồng bộ trong giao dịch, đi vào `sign.api` qua listener, giữ chiều phụ thuộc từ sign đến catalog. V111 đồng bộ dữ liệu cũ.
- A14/B7 đặt bộ kiểm tra ImageIO trong `shared.image` vì catalog và sign dùng cùng các định dạng, không cần thêm phụ thuộc giữa các module. Cạnh tối đa 4096 px, tổng tối đa 4 triệu pixel cho icon và crop, 8 triệu pixel cho ảnh báo cáo.
- A10 dùng phiên bản từ MySQL (V110) cho khoá Redis. Lượt đọc bắt đầu trước khi admin commit chỉ có thể ghi vào khoá của phiên bản cũ.
- A6 dùng ngân sách 25 triệu pixel cho ảnh đang giải mã và giảm mẫu khi đọc ảnh lớn. A15 đệm phần thiếu về phía phải hoặc dưới và cắt phần đệm khỏi box khi quy đổi.
- B2 quét tối đa 250 đề xuất mỗi lô và đọc stream giữa các lô. Dấu Redis giữ ID mục stream và được đối chiếu với mục khi quét bù.
- Đã xử lý đủ các phát hiện được giao.
- Gradle hoàn tất `spotlessCheck test compileTestJava jacocoTestCoverageVerification` với 700 test đạt, 0 test lỗi hoặc bỏ qua. Markdown lint đạt.
- Các ca Testcontainers đã biên dịch và chờ CI vì worktree thiếu Docker. Phạm vi gồm `CatalogAdminIT`, `CatalogApiIT`, `MapIT`, `SubmissionIT` (gồm biến thể IT-B12 nhiều lô), `ReviewIT` và `FeedbackIT`.
