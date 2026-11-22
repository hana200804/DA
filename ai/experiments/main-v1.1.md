# Lượt so sánh chính trên v1.1

Các lượt chính dùng ảnh 640, khởi tạo COCO, seed 0 và cùng bảng tăng cường. YOLO chọn checkpoint và tính patience 30 epoch theo mAP@0.5:0.95 của validator nội bộ trên val. DETR-R50 dùng `signa_ai.evaluate` cho hai bước này. So sánh cuối dùng `signa_ai.evaluate` cho cả năm checkpoint trên **val** v1.1 (1.599 ảnh, 2.685 box), với dự đoán xuất ở conf 0,001. [Thiết kế AI §3](../../docs/04-thiet-ke-ai.md#3-giao-thức-huấn-luyện), [Bảng tăng cường](../configs/augment.md), [Kết quả trên test](test-v1.1.md)

| Mô hình | Epoch chạy / chọn | mAP@0.5 | mAP@0.5:0.95 | AP box nhỏ | F1 tối đa (ngưỡng) | Recall biển cấm | GPU · giờ |
| --- | --- | --- | --- | --- | --- | --- | --- |
| YOLOv5n | 200 / 171 | 78,35% | 61,58% | 51,0% | 81,3% (0,39) | **83,58%** | L4 · 6,7 |
| YOLOv5s | 200 / 183 | 81,41% | 65,97% | **52,5%** | 83,5% (0,42) | 82,74% | L4 · 6,9 |
| YOLOv8n | 187 / 157 | 81,46% | 65,57% | 51,2% | 82,8% (0,43) | 80,45% | L4 · 5,5 |
| YOLOv8s | 196 / 166 | **84,03%** | **69,56%** | 51,1% | **85,0%** (0,53) | 79,75% | L4 · 6,1 |
| [DETR-R50](detr-r50-640-v1.1-main-s0/) | 249 / 219 | 76,09% | 56,79% | 37,4% | 79,1% (0,71) | 75,43% | A100 · 14,6 |

Hồ sơ từng lượt nằm trong thư mục cùng tên: `config.yaml`, `meta.json`, `results.csv`, `best-selection.json`, `metrics.json`. Trọng số ở Drive `Signa/experiments/<mã thí nghiệm>/`.

## Ghi chú

- Trần epoch là 200 cho YOLO, 300 cho DETR. Hai lượt YOLOv5 chạm trần trước khi đủ 30 epoch không tăng. DETR dừng sớm ở epoch 249.
- Recall biển cấm được đo ở ngưỡng F1 tối đa của từng mô hình. Giờ GPU trên L4 và A100 không so sánh trực tiếp được. Giờ GPU của YOLO chỉ gồm phiên huấn luyện.
- Điểm mAP@0.5:0.95 tự tính trong `best-selection.json` cao hơn bộ đánh giá chung lần lượt 2,2 điểm phần trăm với YOLOv5n, 2,5 với YOLOv5s, 3,8 với YOLOv8n và 4,1 với YOLOv8s. Cùng checkpoint YOLOv8s tại epoch 166 đạt 69,56% theo bộ chấm chung và 73,63% theo validator. Điểm tự tính của YOLOv8n là 69,39%. Hai quy trình khác nhau về NMS (đa nhãn ở validator, đơn nhãn khi xuất dự đoán), tiền xử lý (batch khi validation, từng ảnh khi xuất), giới hạn số phát hiện (`max_det=300` khi xuất, `maxDets=100` khi tính COCO AP) và cách tính AP. Chưa tách được ảnh hưởng của từng khác biệt. [Điểm chọn YOLOv8n](yolov8n-640-v1.1-main-s0/best-selection.json), [Điểm chọn YOLOv8s](yolov8s-640-v1.1-main-s0/best-selection.json), [Chỉ số val YOLOv8s](yolov8s-640-v1.1-main-s0/metrics.json)
- Theo bộ đánh giá chung, mAP@0.5:0.95 trên val của YOLOv8n là 65,57% ở lượt chính và 67,90% ở lượt tạm `yolov8n-640-v1.1-temp-s0`. Lượt tạm dùng tăng cường mặc định của Ultralytics và trần 100 epoch. Lượt chính dùng tăng cường chung và trần 200 epoch. Với một seed, chưa tách được ảnh hưởng của từng thay đổi. [Nguồn điểm lượt tạm trong mô tả PR #9](https://github.com/hana200804/DA/pull/9)
- Lượt YOLOv5n dừng ở bước chọn epoch sau khi train xong, do YOLOv5 @1e7cf74 ghi cột `metrics/mAP50-95`. Lỗi được sửa ở commit `8de0608`, rồi chạy tiếp mà không train lại (`run_status` = `trained`). Kết quả không đổi.
- Cả bốn lượt YOLO trong bảng đều chạy trọn trong một phiên. Với lượt mới, notebook chính lưu trạng thái dừng sớm theo epoch của `last.pt` và ghi batch theo phiên. Seed Albumentations của YOLOv5, SHA notebook và SHA trọng số COCO khởi tạo cũng áp dụng cho lượt mới.
- Khi diễn giải AP biển nhỏ giữa các họ mô hình, lưu ý DETR và YOLO lọc box sau phép hình học theo các ngưỡng khác nhau. Box nhỏ hoặc bị cắt ở mép ảnh có thể được giữ ở pipeline này và bị loại ở pipeline kia. Chưa đo mức ảnh hưởng lên AP. Lượt YOLOv8s 960 dùng cùng bộ lọc YOLO với lượt 640. [Quy tắc lọc box](../configs/augment.md#cách-áp-dụng)
