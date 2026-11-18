# Quyết định kiến trúc (ADR)

Mỗi quyết định ảnh hưởng nhiều thành phần được ghi thành một Architecture Decision Record theo [mẫu](template.md): bối cảnh, các phương án, đánh đổi và hệ quả.

## Danh sách

| ADR | Quyết định | Trạng thái |
| --- | --- | --- |
| [0001](0001-ghi-nhan-quyet-dinh-bang-adr.md) | Ghi nhận quyết định kiến trúc bằng ADR | Proposed |
| [0002](0002-nhan-dien-tren-server-va-thiet-bi.md) | Nhận diện trên server bằng ba mô hình và trên điện thoại bằng một mô hình nhỏ | Proposed |
| [0003](0003-mot-tang-so-sanh-ba-mo-hinh.md) | Phát hiện một tầng trực tiếp trên mã biển để so sánh công bằng ba mô hình | Proposed |
| [0004](0004-diem-tin-cay-ban-do.md) | Gom cụm đề xuất và điểm tin cậy Beta làm trợ lý phê duyệt | Proposed |
| [0005](0005-backend-mot-khoi-chia-module.md) | Backend một khối chia module, dịch vụ nhận diện Python gọi qua HTTP | Proposed |
| [0006](0006-luu-anh-cat-tren-minio.md) | Chỉ lưu ảnh cắt vùng biển trên MinIO, tải lên qua backend | Proposed |
| [0007](0007-gom-cum-tuan-tu-qua-redis-stream.md) | Gom cụm đề xuất tuần tự qua Redis Stream | Proposed |

## Quy trình

1. Sao chép [template.md](template.md) thành `NNNN-ten-ngan.md`, trạng thái `Proposed`.
2. Mở Pull Request từ nhánh `docs/adr-NNNN-ten-ngan`.
3. Thảo luận trong PR; khi cả nhóm đồng ý thì chuyển `Accepted` và merge.
4. Không sửa nội dung một ADR đã `Accepted`. Muốn thay đổi thì viết ADR mới và chuyển ADR cũ thành `Superseded by ADR-NNNN`.
