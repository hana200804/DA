# Analyzer

Trách nhiệm: tiếp nhận frame từ camera, điều phối xử lý ảnh và chuyển frame phù hợp sang detector. Chuẩn bị xoay/chuyển màu, resize và letterbox theo hợp đồng mô hình; giữ thông tin biến đổi để đưa box về ảnh gốc.

| File dự kiến, chưa tạo | Nhiệm vụ |
| --- | --- |
| CameraFrameAnalyzer.kt | Nhận frame, xử lý lần lượt hoặc bỏ frame cũ, giải phóng frame sau xử lý |
| FramePreprocessor.kt | Tiền xử lý đúng định dạng, kích thước và chuẩn hóa mà mô hình yêu cầu |

Đầu vào: frame camera và thông tin xoay/thời điểm. Đầu ra: dữ liệu ảnh phù hợp detector và thông tin biến đổi tọa độ.

Không cập nhật Compose, gửi API hoặc lưu database ở đây. Không tự chọn chuẩn hóa ảnh khác mã tham chiếu ai/src/signa_ai/mobile.py. Hiện package này mới giữ khung, chưa có analyzer chạy.
