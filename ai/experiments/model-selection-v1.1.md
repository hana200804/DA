# Đề xuất chọn mô hình triển khai v1.1 (AI-04)

## Server (FR-02)

Chọn mô hình có mAP@0.5:0.95 val cao nhất trong các mô hình đạt NFR-02. Đề xuất **YOLOv8s** theo val vì đứng đầu với **69,56%**. Ở ngưỡng F1 tối đa **0,5279324054718018**, precision là **90,31%**, recall **80,22%** và F1 **84,97%**. [Phân tích năm lượt val](error-analysis-v1.1-val.md)

Chưa đo p95 trên server tham chiếu. Q7 chưa chốt. Ở bước sau, dùng notebook đo thời gian đầu cuối từ nhận ảnh đến trả kết quả. Xác nhận p95 ≤ 1 s cho YOLO và ≤ 3 s cho DETR trước khi chốt. [NFR-02](../../docs/02-dac-ta-yeu-cau.md#9-yêu-cầu-phi-chức-năng), [Q7](../../docs/02-dac-ta-yeu-cau.md#11-vấn-đề-cần-làm-rõ)

## Điện thoại (FR-03)

Lọc theo NFR-07 (< 15 MB). Cả tám ứng viên đều qua bộ lọc. Chưa đo FPS (NFR-01) trên Android tham chiếu. Số ms/ảnh dưới đây đo trên CPU Colab, với hai luồng trên Intel Xeon 2,20 GHz. Nguồn là `tflite-summary-yolov5n.csv` và `tflite-summary-yolov8n.csv` trong `~/signa-cache`.

| Mô hình | Ảnh và lượng tử hoá | MB | ms/ảnh CPU Colab | mAP@0.5 val | mAP@0.5:0.95 val |
| --- | --- | --- | --- | --- | --- |
| YOLOv5n | 640-FP16 | 3,76 | 137,40 | 78,34% | 61,47% |
| YOLOv5n | 640-INT8 | 2,06 | 164,50 | 58,46% | 36,63% |
| YOLOv5n | 480-FP16 | 3,75 | 80,75 | 75,82% | 58,03% |
| YOLOv5n | 480-INT8 | 2,05 | 96,66 | 53,31% | 31,15% |
| YOLOv8n | 640-FP16 | 6,20 | 223,16 | 81,50% | 65,55% |
| YOLOv8n | 640-INT8 | 3,28 | 235,45 | 74,62% | 52,91% |
| YOLOv8n | 480-FP16 | 6,16 | 130,30 | 78,47% | 62,49% |
| YOLOv8n | 480-INT8 | 3,26 | 137,65 | 70,40% | 50,55% |

Đề xuất tạm **YOLOv8n TFLite 640 FP16** vì mAP@0.5 val cao nhất (81,50%). Để chốt, đo chuỗi nhận diện khi phát lại video val trên thiết bị tham chiếu. Chọn cấu hình có mAP@0.5 val cao nhất trong các cấu hình đạt ≥ 15 FPS và < 15 MB. [Quy tắc lựa chọn](../../docs/04-thiet-ke-ai.md#chọn-mô-hình-triển-khai--ai-04)

### Ngưỡng ưu tiên recall biển cấm

Tính recall nhóm từ các mã có `category: prohibitory` trong `labels.yaml`, dùng ghép cùng mã tại IoU ≥ 0,5. Từ ngưỡng F1 tối đa, hạ tới ngưỡng cao nhất đạt recall biển cấm ≥ 0,85 (NFR-06). Precision trong bảng tính trên toàn bộ mã. Phép đo dùng dự đoán từng ảnh trước tracker.

| Mô hình TFLite 640 FP16 | Ngưỡng F1 tối đa | Recall biển cấm tại F1 tối đa | Ngưỡng ưu tiên recall | Recall biển cấm tại ngưỡng chọn | Precision tại ngưỡng chọn |
| --- | --- | --- | --- | --- | --- |
| [YOLOv5n](yolov5n-640-v1.1-main-s0/model_card.json) | 0,398440 | 83,44% | 0,240847 | 85,04% | 78,76% |
| [YOLOv8n](yolov8n-640-v1.1-main-s0/model_card.json) | 0,429308 | 80,45% | 0,042588 | 85,04% | 71,95% |

Với YOLOv8n 640 FP16, precision toàn bộ mã giảm từ 86,67% xuống 71,95% khi hạ ngưỡng. Kết quả trên val đạt mức recall biển cấm yêu cầu. Chưa đo chuỗi xử lý có tracker. Nguồn dự đoán là `~/signa-cache/tflite-640-fp16/<mô hình>/predictions-val.json`. Thẻ lưu điểm đo và SHA-256 của nguồn.

## Đối chiếu NFR-05

| Vai trò | mAP@0.5 val | Mục tiêu trên test | Chênh lệch val so với mốc |
| --- | --- | --- | --- |
| Server · YOLOv8s | 84,03% | ≥ 85,00% | −0,97 điểm phần trăm |
| Điện thoại · YOLOv8n 640 FP16 | 81,50% | ≥ 80,00% | +1,50 điểm phần trăm |

Val chỉ ước lượng kết quả. NFR-05 được quyết định bằng mAP@0.5 trên test. [Đặc tả NFR-05](../../docs/02-dac-ta-yeu-cau.md#9-yêu-cầu-phi-chức-năng)

## Chốt cấu hình trước khi đo test

Các cấu hình dự kiến dưới đây dùng ngưỡng vận hành chọn trên val. Năm lượt PyTorch dùng điểm F1 tối đa. Bản điện thoại dự kiến dùng điểm ưu tiên recall. Cấu hình triển khai còn chờ các phép đo hiệu năng. Nếu đề xuất thay đổi sau khi đo, cập nhật cấu hình ở đây trước khi chấm test. **Tập test chưa được chấm.**

| Mô hình | Cạnh ảnh (px) | Ngưỡng vận hành (đầy đủ) | SHA-256 trọng số nguồn |
| --- | --- | --- | --- |
| [YOLOv5n](yolov5n-640-v1.1-main-s0/model_card.json) | 640 | 0,390178382396698 | `1a41dfc18db0a00f2455b9159a902830de2b203d1b9c858a26a7d016be9a9936` |
| [YOLOv5s](yolov5s-640-v1.1-main-s0/model_card.json) | 640 | 0,4176335334777832 | `f47138cc1af5db74e912d0a86ca397eee69edf2f644d2f82383a68452c966a0f` |
| [YOLOv8n](yolov8n-640-v1.1-main-s0/model_card.json) | 640 | 0,43184080719947815 | `5cb3927b7e740ac944b09a569c4dc5547553cec8a5cdae635d8148a456f82a03` |
| [YOLOv8s](yolov8s-640-v1.1-main-s0/model_card.json) | 640 | 0,5279324054718018 | `49012dc0aa68865114f3a09545cab371978e5a52567cced52ebb050f49bf219d` |
| [DETR-R50](detr-r50-640-v1.1-main-s0/model_card.json) | 640 | 0,706530917812261 | `d0f90fc5d92fa406ea2e4d96003c4714e075ca30b41f48febd3f68e57a50a179` |
| YOLOv8n TFLite FP16 | 640 | 0,04258768633008003 | `5cb3927b7e740ac944b09a569c4dc5547553cec8a5cdae635d8148a456f82a03` |

SHA-256 trọng số nguồn lấy từ `~/signa-cache/val-v1.1/<lượt>/export-config.json`. File điện thoại là `model.tflite` có SHA-256 `0af0fcaef4eefd4899c2ec85bd833bec8aa3f586b04f18acb63728dc074df159`, theo `tflite-640-fp16/yolov8n/export-meta.json` trong cache. Dùng ngưỡng 0,001 khi xuất COCO để tính AP. Dùng ngưỡng vận hành trong bảng khi tính precision, recall và lỗi tại điểm vận hành.

## Việc còn mở

- Chốt thiết bị, server tham chiếu (Q7), đo FPS thiết bị (NFR-01) và p95 đầu cuối trên server bằng notebook (NFR-02).
- Chấm test theo cấu hình đã chốt để kiểm tra NFR-05. Tập thực địa cho AI-03 chưa có.
- Mỗi lượt chính chỉ dùng một seed. Chưa có độ lệch giữa các seed. [Hồ sơ huấn luyện](main-v1.1.md)
