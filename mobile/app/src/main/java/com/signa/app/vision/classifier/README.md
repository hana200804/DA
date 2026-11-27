# Classifier

Giữ package theo cấu trúc đã chuẩn bị. Đây là nhánh tùy chọn cho pipeline có mô hình phân loại ảnh cắt riêng.

| File dự kiến, chưa tạo | Nhiệm vụ |
| --- | --- |
| SignClassifier.kt | Hợp đồng phân loại ảnh biển đã cắt, khi có yêu cầu mô hình cụ thể |

Đầu vào dự kiến: ảnh cắt vùng biển. Đầu ra dự kiến: mã/nhãn và confidence.

Thiết kế YOLO hiện hành nhận diện trực tiếp mã biển, nên chưa nối classifier vào luồng bắt buộc. Không tạo classifier giả hoặc thêm bước suy luận thừa. Chỉ triển khai khi hợp đồng AI thay đổi hoặc nhóm chọn pipeline có bước này.
