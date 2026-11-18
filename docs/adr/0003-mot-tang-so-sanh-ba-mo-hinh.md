# ADR-0003: Phát hiện một tầng trực tiếp trên mã biển để so sánh công bằng ba mô hình

**Status**: Proposed

## Bối cảnh

Đóng góp nghiên cứu chính của đề tài là so sánh **YOLOv5, YOLOv8 và DETR** cho biển báo Việt Nam (AI-02, AI-03). Một phép so sánh chỉ có giá trị nếu ba mô hình giải **cùng một bài toán** trên cùng dữ liệu.

Danh mục QCVN 41 có nhiều mã rất giống nhau (cùng hình tròn viền đỏ, khác ký hiệu; biển tốc độ chỉ khác con số) và mất cân bằng lớp mạnh. Thiết kế trước đây giải quyết bằng hai tầng: detector phát hiện 5 nhóm, classifier phân loại mã trên ảnh cắt.

Ràng buộc: nhóm 3 người, 16 tuần; một người phụ trách toàn bộ phần AI.

## Quyết định

Chọn phương án A: cả ba mô hình là **bộ phát hiện một tầng**, mỗi lớp là một mã biển trong danh mục nhãn ([Dữ liệu §2](../05-du-lieu.md#2-danh-mục-nhãn)); mã quá hiếm gộp vào `other_<nhóm>`.

## Các phương án đã xem xét

### Phương án A: Một tầng, lớp là mã biển

| Tiêu chí | Đánh giá |
| --- | --- |
| Độ phức tạp | Thấp — một mô hình mỗi kiến trúc, một pipeline đánh giá |
| Chi phí | Ba lần huấn luyện cho ba kiến trúc |
| Khả năng mở rộng | Thêm mã phải huấn luyện lại toàn bộ detector |
| Mức quen thuộc với nhóm | Cao — đúng cách dùng chuẩn của cả ba bộ công cụ |

**Ưu điểm:** so sánh trực tiếp và công bằng; DETR dùng đúng thế mạnh (dự đoán tập, không NMS); một mô hình trên điện thoại.

**Nhược điểm:** lớp hiếm có ít mẫu ảnh hưởng tới detector; nhiều lớp làm detector nhỏ khó phân biệt mã giống nhau.

### Phương án B: Hai tầng — phát hiện theo nhóm, phân loại mã

| Tiêu chí | Đánh giá |
| --- | --- |
| Độ phức tạp | Cao — hai mô hình mỗi cấu hình, lịch chạy classifier, kiểm tra chéo |
| Chi phí | Thêm huấn luyện và đánh giá classifier |
| Khả năng mở rộng | Tốt — thêm mã chỉ cần huấn luyện lại classifier |
| Mức quen thuộc với nhóm | Trung bình |

**Ưu điểm:** classifier xử lý tốt khác biệt nhỏ giữa các mã trên ảnh cắt rõ.

**Nhược điểm:** so sánh ba mô hình trở thành so sánh ba detector *nhóm* — phần khó nhất (phân biệt mã) do classifier chung đảm nhiệm, nên khác biệt giữa YOLOv5, YOLOv8, DETR bị che đi; khối lượng việc gần gấp đôi cho một người.

### Phương án C: Một tầng, lớp là nhóm biển

| Tiêu chí | Đánh giá |
| --- | --- |
| Độ phức tạp | Thấp nhất |
| Chi phí | Thấp |
| Khả năng mở rộng | — |
| Mức quen thuộc với nhóm | Cao |

**Ưu điểm:** dữ liệu mỗi lớp nhiều, mô hình học ổn định.

**Nhược điểm:** không trả lời được "đây là biển gì" — không đáp ứng FR-02, FR-08 và bản đồ theo mã.

## Phân tích đánh đổi

C không đáp ứng yêu cầu. Giữa A và B: B có thể chính xác hơn với mã hiếm, nhưng làm hỏng phép so sánh ba mô hình — chính là nội dung đề tài — và tốn gấp đôi công cho một người. A chấp nhận điểm yếu ở lớp hiếm và giảm thiểu bằng gộp lớp, dữ liệu tổng hợp và báo cáo theo lớp. Nếu còn thời gian, hai tầng có thể được thêm như một **thí nghiệm** trên mô hình tốt nhất, không thay đổi thiết kế hệ thống.

## Hệ quả

- Dễ hơn: một bộ đánh giá chung cho cả ba mô hình; triển khai một mô hình trên điện thoại.
- Khó hơn: danh mục phải đủ mẫu mỗi lớp; thêm mã phải huấn luyện lại.
- Cần xem lại: nếu recall các mã giống nhau (ví dụ các mức tốc độ) quá thấp ở baseline tuần 7, thử thêm tầng phân loại cho riêng nhóm biển tốc độ.

## Việc cần làm

1. [ ] Chốt danh mục v1 với ngưỡng ≥ 100 box train mỗi lớp. Báo cáo số box test theo lớp sau khi chốt danh mục
2. [ ] Bộ đánh giá chung bằng `pycocotools` ([Thiết kế AI §4](../04-thiet-ke-ai.md#4-giao-thức-đánh-giá-và-chọn-mô-hình))
3. [ ] Báo cáo ma trận nhầm lẫn và recall theo lớp cho cả ba mô hình
