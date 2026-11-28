# Detector

| Tệp | Trách nhiệm |
| --- | --- |
| `TfliteSignDetector.kt` | Đọc metadata, nhãn và mô hình từ assets, kiểm tra SHA-256, kiểm tra kiểu và kích thước tensor, chạy LiteRT CPU XNNPACK với 2 luồng. |
| `DetectionDecoder.kt` | Lấy lớp có điểm cao nhất mỗi anchor, lọc ngưỡng, chiếu box về ảnh gốc và loại box nằm toàn bộ ngoài ảnh. NMS theo lớp dùng box chưa cắt, sau đó trả box đã cắt. |

YOLOv8n 640 FP16 có đầu vào float32 `[1,640,640,3]` và đầu ra float32 `[1,50,8400]`. Metadata ghi ngưỡng `0.04258768633008003`, NMS IoU `0.7`, tối đa 300 box và `labelMapVersion = 1`. `labels.json` được sinh từ `ai/data/labels.yaml`. Ngưỡng và thứ tự xử lý khớp mã tham chiếu `ai/src/signa_ai/mobile.py`.
