# ADR-0005: Backend một khối chia module, dịch vụ nhận diện Python gọi qua HTTP

**Status**: Proposed

## Bối cảnh

Nghiệp vụ (tài khoản, phân quyền, danh mục, biển, phê duyệt, lịch sử) phù hợp với Java Spring Boot. Chạy YOLOv5, YOLOv8, DETR phía server phải dùng Python vì hệ sinh thái học sâu. Hệ thống triển khai trên một máy chủ bằng Docker Compose, tải nhỏ ([Kiến trúc §7](../03-kien-truc.md#7-ước-lượng-tải)).

Thiết kế trước đây tách ba service Java (auth, map, sign) và gọi ai-service qua gRPC. Đề tài không yêu cầu microservices. Nhóm chỉ có một người phụ trách toàn bộ backend và web trong 16 tuần.

## Quyết định

Chọn phương án B:

- **Một ứng dụng Spring Boot** chia module theo nghiệp vụ: `identity`, `catalog`, `sign`, `recognition`, `audit`. Module chỉ gọi nhau qua gói `api` công khai; ranh giới kiểm tra bằng ArchUnit.
- **Một dịch vụ Python** (FastAPI) chỉ làm suy luận, không trạng thái, gọi qua **HTTP REST** trong mạng nội bộ.

## Các phương án đã xem xét

### Phương án A: Microservices Java + gRPC

| Tiêu chí | Đánh giá |
| --- | --- |
| Độ phức tạp | Cao — 3 service, thư viện dùng chung, proto, xác thực giữa service |
| Chi phí | Nhiều container, nhiều cấu hình |
| Khả năng mở rộng | Tốt — mở rộng riêng từng service |
| Mức quen thuộc với nhóm | Thấp — một người vận hành nhiều service |

**Ưu điểm:** ranh giới cứng giữa nghiệp vụ; nhiều người làm song song không đụng nhau.

**Nhược điểm:** giao dịch xuyên service (ví dụ phê duyệt + lịch sử + khoá tài khoản) phải xử lý bất đồng bộ; test tích hợp phức tạp; lợi ích làm song song không có vì chỉ một người làm backend.

### Phương án B: Một khối chia module + dịch vụ Python qua HTTP

| Tiêu chí | Đánh giá |
| --- | --- |
| Độ phức tạp | Thấp |
| Chi phí | Hai container ứng dụng |
| Khả năng mở rộng | Đủ cho tải đồ án; tách module thành service được nhờ ranh giới rõ |
| Mức quen thuộc với nhóm | Cao |

**Ưu điểm:** một giao dịch CSDL cho thay đổi và lịch sử (NFR-11); một lần triển khai; test tích hợp bằng Testcontainers đơn giản; FastAPI sinh OpenAPI cho dịch vụ nhận diện.

**Nhược điểm:** không mở rộng riêng từng phần nghiệp vụ; kỷ luật ranh giới module phải giữ bằng test.

### Phương án C: Một khối, chạy mô hình trong Java (ONNX Runtime / DJL)

| Tiêu chí | Đánh giá |
| --- | --- |
| Độ phức tạp | Cao — xuất cả ba mô hình sang ONNX, tự viết tiền xử lý và hậu xử lý bằng Java |
| Chi phí | Một container |
| Khả năng mở rộng | Suy luận nặng chạy chung tiến trình với nghiệp vụ |
| Mức quen thuộc với nhóm | Thấp |

**Ưu điểm:** không có dịch vụ thứ hai.

**Nhược điểm:** xử lý của DETR, YOLOv5 khác nhau, viết lại bằng Java dễ lệch với kết quả đánh giá Python; suy luận chậm làm chậm cả API nghiệp vụ.

## Phân tích đánh đổi

A mua khả năng mở rộng mà đồ án không cần, bằng độ phức tạp mà một người không gánh nổi. C bỏ được một container nhưng có nguy cơ kết quả trong hệ thống khác kết quả đã báo cáo. B giữ đúng mã suy luận đã dùng khi đánh giá, cô lập tải suy luận khỏi nghiệp vụ, và giữ phần còn lại đơn giản. HTTP được chọn thay gRPC vì chỉ có hai endpoint, payload là một ảnh, và dễ kiểm thử bằng công cụ thông thường.

## Hệ quả

- Dễ hơn: phát triển, kiểm thử, triển khai backend; giao dịch nhất quán cho lịch sử xử lý.
- Khó hơn: giữ ranh giới module khi phát triển các luồng đi qua nhiều module.
- Cần xem lại: nếu một module cần nhịp phát hành hoặc tài nguyên khác hẳn, tách module đó thành service.

## Việc cần làm

1. [ ] Khởi tạo một ứng dụng Gradle trong `backend/` với các gói module
2. [ ] Test ArchUnit: module chỉ phụ thuộc gói `api` của module khác
3. [ ] Dịch vụ nhận diện FastAPI với `/v1/detect`, `/v1/models`, `/healthz` ([API §7](../07-api.md#7-dịch-vụ-nhận-diện-nội-bộ))
