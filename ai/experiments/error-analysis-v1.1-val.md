# Phân tích lỗi trên val v1.1

Năm lượt chính dùng cùng 1599 ảnh và 2685 box GT. Dự đoán PyTorch được xuất ở ngưỡng 0,001. Bộ đánh giá chung dùng `pycocotools` cho AP tại IoU 0,5:0,95, trừ cột mAP@0.5. Diện tích box tính trên ảnh gốc theo ba nhóm < 32² px, từ 32² đến 96² px, và > 96² px. [Giao thức đánh giá](../../docs/04-thiet-ke-ai.md#4-giao-thức-đánh-giá-và-chọn-mô-hình)

Ma trận nhầm lẫn dùng hàng là mã GT và cột là mã dự đoán. Tại ngưỡng F1 tối đa của mỗi mô hình, ghép theo điểm tin cậy giảm dần với box GT còn trống có IoU lớn nhất (≥ 0,5), cho phép khác mã. FN bỏ sót là GT ghép vào cột `background`. FP nền là dự đoán ghép vào hàng `background`, gồm cả box trùng hoặc định vị chưa đủ IoU. Recall từng mã dùng phép ghép cùng mã tại cùng ngưỡng.

## Kết quả theo kích thước

| Mô hình | mAP@0.5 | mAP@0.5:0.95 | AP nhỏ | AP vừa | AP lớn | Ngưỡng F1 tối đa | FN bỏ sót | FP nền |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| [YOLOv5n](yolov5n-640-v1.1-main-s0/error-analysis-val.json) | 78,35% | 61,58% | 50,99% | 68,24% | 57,81% | 0,390178 | 346 | 201 |
| [YOLOv5s](yolov5s-640-v1.1-main-s0/error-analysis-val.json) | 81,41% | 65,97% | 52,46% | 70,54% | 70,20% | 0,417634 | 331 | 194 |
| [YOLOv8n](yolov8n-640-v1.1-main-s0/error-analysis-val.json) | 81,46% | 65,57% | 51,25% | 70,76% | 72,03% | 0,431841 | 388 | 156 |
| [YOLOv8s](yolov8s-640-v1.1-main-s0/error-analysis-val.json) | 84,03% | 69,56% | 51,15% | 74,34% | 76,09% | 0,527932 | 419 | 119 |
| [DETR-R50](detr-r50-640-v1.1-main-s0/error-analysis-val.json) | 76,09% | 56,79% | 37,41% | 65,42% | 62,11% | 0,706531 | 501 | 177 |

## Mười cặp nhầm nhiều nhất mỗi mô hình

Chỉ lấy cặp khác mã đã ghép được với IoU ≥ 0,5. Với cùng số lượt, xếp theo mã GT rồi mã dự đoán.

| Mô hình | Hạng | Mã GT | Mã dự đoán | Số lượt |
| --- | --- | --- | --- | --- |
| YOLOv5n | 1 | `P.124a` | `P.103c` | 20 |
| YOLOv5n | 2 | `other_warning` | `W.203c` | 17 |
| YOLOv5n | 3 | `I.409` | `I.407a` | 15 |
| YOLOv5n | 4 | `I.407a` | `I.409` | 10 |
| YOLOv5n | 5 | `P.123a` | `P.106a` | 7 |
| YOLOv5n | 6 | `W.203c` | `W.203a` | 7 |
| YOLOv5n | 7 | `W.245a` | `W.205` | 7 |
| YOLOv5n | 8 | `W.201a` | `W.210` | 6 |
| YOLOv5n | 9 | `W.224` | `W.201a` | 6 |
| YOLOv5n | 10 | `other_prohibitory` | `P.127_80` | 6 |
| YOLOv5s | 1 | `other_warning` | `W.203c` | 19 |
| YOLOv5s | 2 | `P.124a` | `P.103c` | 10 |
| YOLOv5s | 3 | `P.127_60` | `other_prohibitory` | 9 |
| YOLOv5s | 4 | `other_warning` | `W.201a` | 9 |
| YOLOv5s | 5 | `W.203c` | `W.203a` | 6 |
| YOLOv5s | 6 | `W.224` | `W.201a` | 6 |
| YOLOv5s | 7 | `other_warning` | `W.245a` | 6 |
| YOLOv5s | 8 | `I.407a` | `I.409` | 5 |
| YOLOv5s | 9 | `P.123b` | `P.127_60` | 5 |
| YOLOv5s | 10 | `P.127_40` | `P.127_80` | 5 |
| YOLOv8n | 1 | `P.124a` | `P.103c` | 20 |
| YOLOv8n | 2 | `W.224` | `W.225` | 9 |
| YOLOv8n | 3 | `W.203c` | `W.203a` | 8 |
| YOLOv8n | 4 | `W.201a` | `W.210` | 7 |
| YOLOv8n | 5 | `P.103a` | `other_prohibitory` | 6 |
| YOLOv8n | 6 | `P.127_60` | `other_prohibitory` | 6 |
| YOLOv8n | 7 | `P.106a` | `P.131a` | 5 |
| YOLOv8n | 8 | `P.127_40` | `P.127_80` | 5 |
| YOLOv8n | 9 | `P.127_50` | `other_prohibitory` | 5 |
| YOLOv8n | 10 | `P.127_80` | `P.127_60` | 5 |
| YOLOv8s | 1 | `P.130` | `P.131a` | 14 |
| YOLOv8s | 2 | `P.124a` | `P.103c` | 11 |
| YOLOv8s | 3 | `P.127_80` | `P.127_60` | 9 |
| YOLOv8s | 4 | `other_warning` | `W.203c` | 8 |
| YOLOv8s | 5 | `P.124a` | `W.203a` | 7 |
| YOLOv8s | 6 | `W.224` | `P.123b` | 6 |
| YOLOv8s | 7 | `other_warning` | `W.207` | 6 |
| YOLOv8s | 8 | `other_warning` | `W.210` | 6 |
| YOLOv8s | 9 | `P.127_60` | `P.127_80` | 5 |
| YOLOv8s | 10 | `other_prohibitory` | `P.127_80` | 5 |
| DETR-R50 | 1 | `P.124a` | `P.103c` | 20 |
| DETR-R50 | 2 | `P.127_50` | `other_prohibitory` | 11 |
| DETR-R50 | 3 | `I.407a` | `I.409` | 10 |
| DETR-R50 | 4 | `P.106a` | `P.105` | 9 |
| DETR-R50 | 5 | `P.127_80` | `P.127_60` | 7 |
| DETR-R50 | 6 | `W.203c` | `W.203a` | 7 |
| DETR-R50 | 7 | `P.127_50` | `P.127_80` | 6 |
| DETR-R50 | 8 | `other_warning` | `W.224` | 6 |
| DETR-R50 | 9 | `I.434a` | `I.409` | 5 |
| DETR-R50 | 10 | `P.106a` | `P.103a` | 5 |

## Năm mã AP thấp nhất mỗi mô hình

Xếp theo AP@0.5:0.95 tăng dần, sau đó theo mã. Chỉ xếp các mã có AP trên val. Cột box GT cho biết số mẫu dùng để tính recall của mã.

| Mô hình | Mã | AP@0.5:0.95 | Recall | Box GT |
| --- | --- | --- | --- | --- |
| YOLOv5n | `P.124a` | 25,98% | 29,41% | 34 |
| YOLOv5n | `I.434a` | 31,13% | 24,44% | 45 |
| YOLOv5n | `W.203c` | 34,30% | 50,00% | 26 |
| YOLOv5n | `R.302a` | 35,52% | 46,75% | 77 |
| YOLOv5n | `P.123b` | 38,32% | 45,95% | 37 |
| YOLOv5s | `W.203c` | 35,97% | 50,00% | 26 |
| YOLOv5s | `P.123a` | 39,31% | 43,33% | 30 |
| YOLOv5s | `P.123b` | 39,42% | 54,05% | 37 |
| YOLOv5s | `I.434a` | 40,11% | 35,56% | 45 |
| YOLOv5s | `P.124a` | 40,82% | 44,12% | 34 |
| YOLOv8n | `P.124c` | 27,45% | 0,00% | 10 |
| YOLOv8n | `P.123b` | 28,08% | 32,43% | 37 |
| YOLOv8n | `P.124a` | 28,11% | 29,41% | 34 |
| YOLOv8n | `P.123a` | 39,05% | 43,33% | 30 |
| YOLOv8n | `P.127_60` | 40,90% | 66,67% | 81 |
| YOLOv8s | `P.124a` | 31,19% | 29,41% | 34 |
| YOLOv8s | `P.124c` | 40,96% | 0,00% | 10 |
| YOLOv8s | `P.123b` | 42,39% | 45,95% | 37 |
| YOLOv8s | `R.302a` | 43,31% | 45,45% | 77 |
| YOLOv8s | `P.127_60` | 46,47% | 82,72% | 81 |
| DETR-R50 | `W.201b` | 22,81% | 32,35% | 34 |
| DETR-R50 | `P.124a` | 24,50% | 29,41% | 34 |
| DETR-R50 | `P.117` | 25,31% | 41,30% | 46 |
| DETR-R50 | `P.124c` | 28,60% | 30,00% | 10 |
| DETR-R50 | `P.127_60` | 29,34% | 60,49% | 81 |

## Đối chiếu giả thuyết

Mỗi cấu hình chỉ dùng một seed. Chưa có độ lệch giữa các seed, nên chênh lệch vài phần mười điểm chưa đủ để kết luận. [Hồ sơ huấn luyện](main-v1.1.md)

- Ở cỡ n, YOLOv8 hơn YOLOv5 3,99 điểm phần trăm mAP@0.5:0.95 và 0,26 điểm AP nhỏ.
- Ở cỡ s, YOLOv8 hơn 3,60 điểm mAP@0.5:0.95 nhưng kém 1,31 điểm AP nhỏ.
- Giả thuyết YOLOv8 cùng cỡ chính xác hơn phù hợp với mAP chung của cả hai cỡ. Phần "nhất là biển nhỏ" chưa được ủng hộ. Mức +0,26 điểm ở cỡ n chưa đủ để kết luận với một seed, còn ở cỡ s chênh lệch là âm.
- DETR-R50 đạt AP nhỏ 37,41%, thấp hơn bốn YOLO 13,58–15,05 điểm. Ở nhóm vừa, DETR-R50 đạt 65,42%, thấp hơn 2,82–8,92 điểm. Ở nhóm lớn, DETR-R50 đạt 62,11%, cao hơn YOLOv5n (57,81%) nhưng thấp hơn ba YOLO còn lại. Khoảng cách lớn nhất nằm ở nhóm nhỏ, phù hợp với giả thuyết DETR yếu với biển nhỏ. Các chỉ số này chưa kiểm chứng vai trò của ngữ cảnh toàn ảnh hay tốc độ hội tụ.

## Phần chưa đo

- AP theo ngày, đêm, mưa. Cột `tags` của manifest v1.1 trống ở cả 1.599 ảnh val. Trong đối tượng `by_condition`, ba khóa `day`, `night` và `rain` có giá trị `null`. Cần gắn thẻ điều kiện cho val rồi chấm lại. [Chỉ số val YOLOv8s](yolov8s-640-v1.1-main-s0/metrics.json)
- Độ trễ HTTP trên server tham chiếu theo NFR-02. Đã có số đo pipeline CPU và GPU trong tiến trình Python trên ảnh val ở laptop và Colab. [Thời gian pipeline tham khảo](test-v1.1.md#thời-gian-pipeline-trên-các-máy-sẵn-có)

Hồ sơ huấn luyện lưu số tham số, GFLOPs, kích thước năm checkpoint PyTorch và giờ GPU huấn luyện. Đề xuất triển khai ghi số ms/ảnh TFLite trên CPU Colab. [Chi phí checkpoint](main-v1.1.md#chi-phí-checkpoint), [Đề xuất triển khai](model-selection-v1.1.md#điện-thoại-fr-03)

## Chạy lại

Chạy trong `ai/`. Nguồn nằm trên Drive chung của nhóm:

- Dự đoán PyTorch: `My Drive/Signa/experiments/<lượt>/evaluation/predictions-val.json`.
- GT val: `coco/val.json` trong `signa-v1.1.zip`, giải nén trong `ai/data/processed/` để có `data/processed/v1.1/coco/val.json` ([Bộ dữ liệu v1.1](../data/README.md#bộ-dữ-liệu-v11)). SHA-256 là `e3ccd276014eadd148030941675efeaf06db1c1ad8deddcd80747d40ec8b08e2`.

Tệp JSON theo từng mô hình ghi SHA-256 của GT và dự đoán đã đọc. Đối chiếu SHA-256 trước khi so kết quả.

```sh
drive="/path/to/My Drive/Signa"
run=yolov8s-640-v1.1-main-s0
.venv/bin/python scripts/analyze_errors.py \
  --gt data/processed/v1.1/coco/val.json \
  --pred "$drive/experiments/$run/evaluation/predictions-val.json" \
  --labels data/labels.yaml \
  --out "experiments/$run/error-analysis-val.json"
```
