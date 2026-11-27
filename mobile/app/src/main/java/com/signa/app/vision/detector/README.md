# Detector

Trách nhiệm: nạp/chạy mô hình nhận diện điện thoại và giải mã đầu ra, lọc theo ngưỡng/NMS đúng hợp đồng AI.

| File dự kiến, chưa tạo | Nhiệm vụ |
| --- | --- |
| SignDetector.kt | Hợp đồng nhận diện ảnh, không gắn với một implementation cụ thể |
| TfliteSignDetector.kt | Thực thi mô hình TFLite, quản lý runtime và tài nguyên |
| DetectionDecoder.kt | Giải mã output và đưa box về hệ tọa độ ảnh thống nhất |

Đầu vào: ảnh đã tiền xử lý. Đầu ra: mã biển, confidence, box và thời điểm; kèm phiên bản nhãn/mô hình ở mức phù hợp. Không trả tensor thô lên UI.

Nguồn hợp đồng là ai/src/signa_ai/mobile.py, ai/data/labels.yaml và metadata đúng model.tflite. Model YOLO hiện tại nhận diện trực tiếp mã biển; detector không mặc định bắt buộc đi qua classifier riêng. Hiện chưa tích hợp TFLite trong package này.
