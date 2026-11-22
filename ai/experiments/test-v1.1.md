# Đánh giá test v1.1 của năm checkpoint PyTorch

Tập test VN82 v1.1 có **1.700 ảnh và 2.664 box**. Năm checkpoint đều lấy từ lượt chính ảnh 640, seed 0. COCO AP dùng dự đoán xuất từ confidence 0,001. Precision, recall và recall nhóm biển cấm dùng IoU ghép box ≥ 0,5 và ngưỡng vận hành đã chốt trên val. Recall biển cấm gồm biển tốc độ theo danh mục v1. [Cấu hình khóa trước khi chấm](model-selection-v1.1.md#chốt-cấu-hình-trước-khi-đo-test)

| Mô hình | mAP@0.5 | mAP@0.5:0.95 | AP nhỏ | Precision | Recall | Recall biển cấm |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| [YOLOv5n](yolov5n-640-v1.1-main-s0/evaluation/) | 77,75% | 60,27% | 51,58% | 83,52% | 80,44% | 84,26% |
| [YOLOv5s](yolov5s-640-v1.1-main-s0/evaluation/) | **82,19%** | 66,49% | **53,54%** | 88,78% | 82,28% | 83,98% |
| [YOLOv8n](yolov8n-640-v1.1-main-s0/evaluation/) | 78,93% | 63,86% | 50,68% | 86,14% | 79,54% | 81,66% |
| [YOLOv8s](yolov8s-640-v1.1-main-s0/evaluation/) | 81,11% | **66,50%** | 52,37% | 89,99% | 81,01% | 80,53% |
| [DETR-R50](detr-r50-640-v1.1-main-s0/evaluation/) | 73,65% | 55,81% | 39,63% | 83,97% | 72,97% | 73,86% |

YOLOv8s là ứng viên server đã đề xuất theo val. Kết quả test của nó thấp hơn mục tiêu NFR-05 là 3,89 điểm phần trăm. Kết quả mAP@0.5 cao nhất trên test là 82,19%, thấp hơn mục tiêu 2,81 điểm. Giữ nguyên cách chọn trên val cho lần đánh giá này. [Bảng số liệu CSV](test-v1.1.csv), [NFR-05](../../docs/02-dac-ta-yeu-cau.md#9-yêu-cầu-phi-chức-năng)

`metrics-test.json` của mỗi lượt có AP theo kích thước, theo lớp và ma trận nhầm lẫn. Manifest VN82 v1.1 thiếu thẻ ngày, đêm và mưa nên các trường AP theo điều kiện là `null`. Bộ thực địa còn thiếu video, GPS đồng bộ và nhãn chuẩn theo tuyến. TFLite và chuỗi camera trên điện thoại có phép đo riêng.

## Thời gian pipeline trên các máy sẵn có

Đo từng ảnh ở ngưỡng vận hành, từ bytes JPEG đã nạp trong bộ nhớ tới box theo pixel ảnh gốc. Dùng cùng 200 ảnh đầu của COCO val theo thứ tự trong tệp, khởi động bằng 10 ảnh đầu. Các tệp `latency.json` lưu cấu hình, p50, p95, trung bình và danh sách ID ảnh.

| Mô hình | Nơi đo | p95 CPU (ms) | p95 GPU (ms) |
| --- | --- | ---: | ---: |
| YOLOv5n | Laptop, Core i5-11400H (4 luồng PyTorch), RTX 3050 | 135,4 | 25,9 |
| YOLOv5s | Laptop, Core i5-11400H (4 luồng PyTorch), RTX 3050 | 234,8 | 16,9 |
| YOLOv8n | Colab, Xeon 2,00 GHz (1 luồng PyTorch), T4 | 270,5 | 19,7 |
| YOLOv8s | Colab, Xeon 2,00 GHz (1 luồng PyTorch), T4 | 555,9 | 16,2 |
| DETR-R50 | Colab, Xeon 2,00 GHz (2 luồng PyTorch), T4 | 2.712,6 | 54,1 |

Các số đo phục vụ tham khảo vì khác máy, số luồng và tải nền. Laptop chạy thêm phép chấm TFLite trong lúc đo YOLOv5. Phép đo pipeline chạy trong tiến trình Python. NFR-02 cần đo đường dẫn HTTP trên server demo 4 vCPU, 8 GB RAM theo Q7. [Giao thức hiệu năng](../../docs/08-kiem-thu.md#3-đánh-giá-và-so-sánh-mô-hình)

## Nguồn và tái tạo

- YOLOv8n, YOLOv8s và DETR-R50 lấy từ `My Drive/Signa/experiments/<lượt>/evaluation/`. Giữ nguyên các tệp chấm, cấu hình, tái tạo val và thời gian của phiên Colab. Đánh giá lại JSON dự đoán gốc trên máy cục bộ cho kết quả trùng toàn bộ `metrics-test.json` của từng lượt.
- Hai lượt YOLOv5 dùng `best.pt` từ `My Drive/Signa/experiments/<lượt>/weights/`, khớp SHA-256 trong thẻ. Chạy thuật toán trong [notebook đã qua review](https://github.com/hana200804/DA/blob/9c5ef17369992c9c934d6757598c25c0dd62c986/ai/notebooks/predict_test_v1.1.ipynb), nhóm `yolov5`, trên GPU laptop. Thay ô cài đặt và mount Drive bằng các đường dẫn cục bộ, giữ nguyên hàm xuất dự đoán và bộ chấm. Phiên bản `ultralytics==8.4.215` lấy từ `requirements.txt` của cả hai lượt huấn luyện. Sai khác tái tạo val nhỏ hơn 0,02 điểm phần trăm ở cả hai chỉ số AP.
- `provenance.json` lưu SHA-256 của dự đoán test, danh mục nhãn và các tệp đi kèm. `test-config.json` lưu SHA-256 checkpoint, GT, manifest, phiên bản runtime và ngưỡng. Dự đoán cục bộ của hai lượt YOLOv5 nằm trong `.local-eval/experiments/<lượt>/evaluation/` ở workspace chạy. Có thể dựng lại dự đoán bằng notebook với trọng số từ Drive. Git lưu chỉ số, cấu hình và hash.
- Dữ liệu từ `signa-v1.1.zip`. SHA-256 của `coco/test.json` là `38cad06f9b0c2960d4c4049f0ebd0de71f8bcefbef472022af10acc0031a9950`. SHA-256 của `coco/val.json` là `e3ccd276014eadd148030941675efeaf06db1c1ad8deddcd80747d40ec8b08e2`. Bản giải nén cục bộ có manifest giống Git và tất cả 9.978 ảnh khớp hash trong manifest. [Bộ dữ liệu v1.1](../data/README.md#bộ-dữ-liệu-v11)

Để chấm lại dự đoán đã có, chạy từ `ai/` với extra `eval`. Lấy ngưỡng đầy đủ từ `test-config.json` của đúng lượt.

```sh
dataset=data/processed/v1.1
run=yolov5s-640-v1.1-main-s0
threshold=0.4176335334777832
uv run --no-sync python -m signa_ai.evaluate \
  --gt "$dataset/coco/test.json" --manifest "$dataset/manifest.csv" \
  --labels data/labels.yaml --pred "/đường/dẫn/$run/evaluation/predictions-test.json" \
  --threshold "$threshold" --out /tmp/signa-metrics-test.json
```
