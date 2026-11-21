# Lượt so sánh chính trên v1.1

Kết quả lượt so sánh chính theo [Thiết kế AI §3](../../docs/04-thiet-ke-ai.md#3-giao-thức-huấn-luyện): ảnh 640, khởi tạo COCO, seed 0, dừng sớm khi mAP@0.5:0.95 trên val không tăng sau 30 epoch, cùng bảng tăng cường [`configs/augment.md`](../configs/augment.md). Mọi số đo dưới đây do bộ đánh giá chung `signa_ai.evaluate` chấm trên **val** v1.1 (1599 ảnh, 2685 box), dự đoán xuất ở conf 0,001. Chưa chấm tập test.

| Mô hình | Epoch chạy / chọn | mAP@0.5 | mAP@0.5:0.95 | AP box nhỏ | F1 tối đa (ngưỡng) | Recall biển cấm | GPU · giờ |
| --- | --- | --- | --- | --- | --- | --- | --- |
| YOLOv5n | 200 / 171 | 78,35% | 61,58% | 51,0% | 81,3% (0,39) | **83,58%** | L4 · 6,7 |
| YOLOv5s | 200 / 183 | 81,41% | 65,97% | **52,5%** | 83,5% (0,42) | 82,74% | L4 · 6,9 |
| YOLOv8n | 187 / 157 | 81,46% | 65,57% | 51,2% | 82,8% (0,43) | 80,45% | L4 · 5,5 |
| YOLOv8s | 196 / 166 | **84,03%** | **69,56%** | 51,1% | **85,0%** (0,53) | 79,75% | L4 · 6,1 |
| DETR-R50 | 249 / 219 | 76,09% | 56,79% | 37,4% | 79,1% (0,71) | 75,43% | A100 · 14,6 |

Hồ sơ từng lượt nằm trong thư mục cùng tên: `config.yaml`, `meta.json`, `results.csv`, `best-selection.json`, `metrics.json`. Trọng số ở Drive `Signa/experiments/<mã thí nghiệm>/`. Hồ sơ DETR-R50 đi cùng nhánh `ai/6-detr-training`.

## Ghi chú

- Trần epoch là 200 cho YOLO, 300 cho DETR. Hai lượt YOLOv5 chạm trần trước khi đủ 30 epoch không tăng. DETR dừng sớm ở epoch 249.
- Điểm do Ultralytics tự tính cao hơn bộ đánh giá chung khoảng 3 điểm (YOLOv8n: 69,2% so với 65,6% mAP@0.5:0.95), vì hai bên nội suy đường PR khác nhau. Bảng trên chỉ dùng bộ đánh giá chung.
- YOLOv8n lượt chính thấp hơn lượt tạm `yolov8n-640-v1.1-temp-s0` (67,90%), vì lượt tạm dùng tăng cường mặc định của Ultralytics. Lượt chính dùng bảng tăng cường chung cho cả ba mô hình.
- Lượt YOLOv5n dừng ở bước chọn epoch sau khi train xong, do YOLOv5 @1e7cf74 ghi cột `metrics/mAP50-95`. Lỗi được sửa ở commit `8de0608`, rồi chạy tiếp mà không train lại (`run_status` = `trained`). Kết quả không đổi.
