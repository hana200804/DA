# Signa

Signa là đồ án nhận diện biển báo giao thông Việt Nam từ ảnh và camera Android. Người dùng có thể tra cứu biển và gửi kết quả nhận diện kèm vị trí. Kiểm duyệt viên xử lý đề xuất trước khi biển được công bố trên bản đồ.

Dự án đang ở giai đoạn thiết kế. Nhóm đang rà soát đặc tả và các ADR trước khi khởi tạo ứng dụng, backend và hạ tầng.

- [Mục lục thiết kế](docs/README.md) dẫn tới yêu cầu, kiến trúc, AI, dữ liệu, API và kiểm thử.
- [Các câu hỏi cần chốt](docs/02-dac-ta-yeu-cau.md#11-vấn-đề-cần-làm-rõ) ghi rõ ai phụ trách và mốc cần câu trả lời.
- [Kế hoạch 16 tuần](docs/09-hang-muc-cong-viec.md) mô tả đầu ra theo thành viên và milestone.
- [Các quyết định kiến trúc](docs/adr/README.md) đang chờ nhóm duyệt.

## Đóng góp tài liệu

Viết tài liệu bằng tiếng Việt và dùng Mermaid cho sơ đồ. Khi đổi yêu cầu, cập nhật đặc tả cùng các tài liệu bị ảnh hưởng trong cùng pull request. ADR ở trạng thái `Accepted` cần ADR thay thế khi đổi quyết định.

Chạy kiểm tra Markdown với Node.js và npm từ thư mục gốc. GitHub Actions chạy cùng lệnh này trên pull request.

```sh
npx --yes markdownlint-cli2@0.18.1 '**/*.md'
```
