# ADR-0002: Nhận diện trên server bằng ba mô hình và trên điện thoại bằng một mô hình nhỏ

**Status**: Proposed

## Bối cảnh

Đề tài yêu cầu ứng dụng **YOLOv5, YOLOv8 và DETR** để nhận diện biển báo từ hình ảnh, và hỗ trợ người dùng **nhận diện** trên ứng dụng di động. Có hai kiểu dùng khác nhau:

- **Ảnh chụp** (FR-02): người dùng chụp một biển lạ để biết ý nghĩa. Chấp nhận chờ 1–3 giây, thường có mạng.
- **Camera liên tục** (FR-03): điện thoại gắn trên xe, cần ≥ 15 FPS (NFR-01) và chạy được khi mất sóng (NFR-08); đây cũng là nguồn đề xuất biển chính cho bản đồ.

DETR (kể cả RT-DETR) quá nặng để đạt 15 FPS trên điện thoại tầm trung. YOLO bản `n` chạy được trên điện thoại sau khi lượng tử hoá.

## Quyết định

Chọn phương án C:

- **Server:** một dịch vụ Python nạp cả ba mô hình, phục vụ nhận diện ảnh chụp; người dùng chọn được mô hình, mặc định là mô hình tốt nhất theo [Thiết kế AI §4](../04-thiet-ke-ai.md#4-giao-thức-đánh-giá-và-chọn-mô-hình).
- **Điện thoại:** một mô hình YOLO nhỏ (v5n hoặc v8n, chọn bằng số liệu) chạy bằng TFLite cho chế độ camera, không cần mạng.

## Các phương án đã xem xét

### Phương án A: Chỉ nhận diện trên server

| Tiêu chí | Đánh giá |
| --- | --- |
| Độ phức tạp | Thấp — một nơi chạy mô hình |
| Chi phí | Server phải xử lý mọi khung hình; băng thông lớn |
| Khả năng mở rộng | Kém — tải tỉ lệ với số khung hình của mọi người dùng |
| Mức quen thuộc với nhóm | Cao |

**Ưu điểm:** dùng được cả ba mô hình cho mọi chức năng; không phải tối ưu cho điện thoại.

**Nhược điểm:** chế độ camera không chạy được khi mất sóng; độ trễ mạng làm kết quả đến muộn; gửi khung hình đầy đủ lộ hình ảnh người và phương tiện xung quanh.

### Phương án B: Chỉ nhận diện trên điện thoại

| Tiêu chí | Đánh giá |
| --- | --- |
| Độ phức tạp | Trung bình — tối ưu mô hình cho điện thoại |
| Chi phí | Không tốn server cho suy luận |
| Khả năng mở rộng | Tốt |
| Mức quen thuộc với nhóm | Trung bình |

**Ưu điểm:** chạy offline; riêng tư.

**Nhược điểm:** DETR không chạy được trên điện thoại, nên **không đáp ứng đề tài**; không có nơi minh hoạ và so sánh ba mô hình trong hệ thống.

### Phương án C: Server ba mô hình, điện thoại một mô hình nhỏ

| Tiêu chí | Đánh giá |
| --- | --- |
| Độ phức tạp | Trung bình — hai nơi chạy mô hình, một danh mục nhãn chung |
| Chi phí | Server chỉ xử lý ảnh chụp, tải thấp |
| Khả năng mở rộng | Tốt — phần tải lớn (camera) nằm trên điện thoại |
| Mức quen thuộc với nhóm | Trung bình |

**Ưu điểm:** cả ba mô hình có vai trò thật trong hệ thống; chế độ camera chạy offline; chỉ ảnh cắt rời điện thoại.

**Nhược điểm:** hai đường triển khai mô hình; kết quả hai chế độ có thể khác nhau với cùng một biển.

## Phân tích đánh đổi

A vi phạm yêu cầu offline và riêng tư; B vi phạm đề tài. C tốn thêm một đường triển khai nhưng đó là cái giá để vừa dùng được DETR vừa có nhận diện thời gian thực. Khác biệt kết quả giữa hai chế độ được kiểm soát bằng một danh mục nhãn chung và bằng việc đánh giá cả hai mô hình trên cùng tập thực địa.

## Hệ quả

- Dễ hơn: trình bày đóng góp C1 và C2 ([Tổng quan §3](../01-tong-quan.md#3-đóng-góp-chính)) bằng hai chức năng có thật; demo không phụ thuộc mạng ở chế độ camera.
- Khó hơn: giữ đồng bộ phiên bản danh mục nhãn giữa server và ứng dụng; đo hiệu năng trên nhiều thiết bị.
- Cần xem lại: nếu điện thoại tham chiếu không đạt 15 FPS với cả v5n và v8n, hạ ảnh đầu vào xuống 480 px trước khi cân nhắc mô hình khác.

## Việc cần làm

1. [ ] Dịch vụ nhận diện với interface `Detector` chung cho ba mô hình ([Thiết kế AI §7](../04-thiet-ke-ai.md#7-dịch-vụ-nhận-diện))
2. [ ] Xuất YOLOv5n, YOLOv8n sang TFLite và đo trên thiết bị tham chiếu
3. [ ] Đánh giá mô hình server và mô hình điện thoại trên cùng tập thực địa
