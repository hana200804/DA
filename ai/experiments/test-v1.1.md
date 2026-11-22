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

Chưa đo số tham số và GFLOPs của năm checkpoint theo giao thức chung. Hai trường `cost.parameters` và `cost.gflops` trong `metrics-test.json` vẫn là `null`. Kết quả đo thời gian CPU và GPU có trong [bảng thời gian pipeline](#thời-gian-pipeline-trên-các-máy-sẵn-có).

## Thời gian pipeline trên các máy sẵn có

Đo từng ảnh ở ngưỡng vận hành, từ bytes JPEG đã nạp trong bộ nhớ tới box theo pixel ảnh gốc. Dùng cùng 200 ảnh đầu của COCO val theo thứ tự trong tệp, khởi động bằng 10 ảnh đầu. Các tệp `latency.json` lưu cấu hình, p50, p95, trung bình và danh sách ID ảnh.

| Mô hình | Nơi đo | p95 CPU (ms) | p95 GPU (ms) |
| --- | --- | ---: | ---: |
| [YOLOv5n](yolov5n-640-v1.1-main-s0/evaluation/latency.json) | Laptop, Core i5-11400H (4 luồng PyTorch), RTX 3050 | 135,4 | 25,9 |
| [YOLOv5s](yolov5s-640-v1.1-main-s0/evaluation/latency.json) | Laptop, Core i5-11400H (4 luồng PyTorch), RTX 3050 | 234,8 | 16,9 |
| [YOLOv8n](yolov8n-640-v1.1-main-s0/evaluation/latency.json) | Colab, Xeon 2,00 GHz (1 luồng PyTorch), T4 | 270,5 | 19,7 |
| [YOLOv8s](yolov8s-640-v1.1-main-s0/evaluation/latency.json) | Colab, Xeon 2,00 GHz (1 luồng PyTorch), T4 | 555,9 | 16,2 |
| [DETR-R50](detr-r50-640-v1.1-main-s0/evaluation/latency.json) | Colab, Xeon 2,00 GHz (2 luồng PyTorch), T4 | 2.712,6 | 54,1 |

Log Colab in cấu hình ban đầu của YOLOv8 là 2 luồng. Khi chọn CPU, Ultralytics 8.3.203 đặt lại số luồng PyTorch theo `NUM_THREADS = min(8, max(1, os.cpu_count() - 1))`. Colab có 2 CPU logic nên hai lượt YOLOv8 đo bằng 1 luồng PyTorch, đúng với số hiệu dụng trong `latency.json`. DETR-R50 giữ 2 luồng PyTorch. [Hàm chọn thiết bị](https://github.com/ultralytics/ultralytics/blob/v8.3.203/ultralytics/utils/torch_utils.py#L219-L226), [Công thức số luồng](https://github.com/ultralytics/ultralytics/blob/v8.3.203/ultralytics/utils/__init__.py#L39-L41)

Các số đo phục vụ tham khảo vì khác máy, số luồng và tải nền. Laptop chạy thêm phép chấm TFLite trong lúc đo YOLOv5. Phép đo pipeline chạy trong tiến trình Python. NFR-02 cần đo đường dẫn HTTP trên server demo 4 vCPU, 8 GB RAM theo Q7. [Giao thức hiệu năng](../../docs/08-kiem-thu.md#3-đánh-giá-và-so-sánh-mô-hình)

## Nguồn và tái tạo

- YOLOv8n, YOLOv8s và DETR-R50 lấy từ `My Drive/Signa/experiments/<lượt>/evaluation/`. Giữ nguyên các tệp chấm, cấu hình, tái tạo val và thời gian của phiên Colab. Đánh giá lại JSON dự đoán gốc trên máy cục bộ cho kết quả trùng toàn bộ `metrics-test.json` của từng lượt.
- Hai lượt YOLOv5 dùng `best.pt` từ `My Drive/Signa/experiments/<lượt>/weights/`, khớp SHA-256 trong thẻ. Huấn luyện trên Colab L4 với Torch 2.14.1+cu130. Chấm test và tái tạo val trên RTX 3050 với Torch 2.15.0 và CUDA 13.2. Phiên bản `ultralytics==8.4.215` lấy từ `requirements.txt` của cả hai lượt huấn luyện. Notebook nhóm `yolov5` chạy với đường dẫn cục bộ và giữ nguyên hàm xuất dự đoán cùng bộ chấm. Trên runtime này, hai chỉ số AP gộp khi tái tạo val đều lệch dưới 0,02 điểm phần trăm. [Notebook đã qua review](https://github.com/hana200804/DA/blob/9c5ef17369992c9c934d6757598c25c0dd62c986/ai/notebooks/predict_test_v1.1.ipynb), [Nhật ký YOLOv5](predict-test-v1.1-yolov5.log.gz)
- `provenance.json` lưu SHA-256 của dự đoán test, danh mục nhãn và các tệp đi kèm. Mục `archives_sha256` ghi hash của bản nén và bản giải nén theo đường dẫn tương đối từ tệp provenance. `test-config.json` lưu SHA-256 checkpoint, GT, manifest, phiên bản runtime và ngưỡng.
- Dữ liệu từ `signa-v1.1.zip`. SHA-256 của `coco/test.json` là `38cad06f9b0c2960d4c4049f0ebd0de71f8bcefbef472022af10acc0031a9950`. SHA-256 của `coco/val.json` là `e3ccd276014eadd148030941675efeaf06db1c1ad8deddcd80747d40ec8b08e2`. Bản giải nén cục bộ có manifest giống Git và tất cả 9.978 ảnh khớp hash trong manifest. [Bộ dữ liệu v1.1](../data/README.md#bộ-dữ-liệu-v11)

### Tệp đầu vào chấm lại

| Lượt | Dự đoán test | Dự đoán tái tạo val |
| --- | --- | --- |
| YOLOv5n | [`predictions-test.json.gz`](yolov5n-640-v1.1-main-s0/evaluation/predictions-test.json.gz) | [`predictions-val-repro.json.gz`](yolov5n-640-v1.1-main-s0/evaluation/predictions-val-repro.json.gz) |
| YOLOv5s | [`predictions-test.json.gz`](yolov5s-640-v1.1-main-s0/evaluation/predictions-test.json.gz) | [`predictions-val-repro.json.gz`](yolov5s-640-v1.1-main-s0/evaluation/predictions-val-repro.json.gz) |
| YOLOv8n | [`predictions-test.json.gz`](yolov8n-640-v1.1-main-s0/evaluation/predictions-test.json.gz) | Thiếu tệp gốc Colab |
| YOLOv8s | [`predictions-test.json.gz`](yolov8s-640-v1.1-main-s0/evaluation/predictions-test.json.gz) | Thiếu tệp gốc Colab |
| DETR-R50 | [`predictions-test.json.gz`](detr-r50-640-v1.1-main-s0/evaluation/predictions-test.json.gz) | Thiếu tệp gốc Colab |

Năm tệp dự đoán test khớp SHA-256 đã ghi trước khi nén. Hồ sơ cũ của hai tệp tái tạo val YOLOv5 chưa có hash. Chấm lại trên CPU cho cùng số dự đoán và hai chỉ số AP trong `reproduce-val.json`. Bản tải về thiếu nhật ký đo của các lượt Colab. `latency.json` của cả năm lượt lưu thống kê gộp. Các lượt đo chưa lưu mẫu thời gian từng ảnh.

Để chấm lại dự đoán đã có, chạy từ `ai/` với extra `eval`. Lấy ngưỡng đầy đủ từ `test-config.json` của đúng lượt.

```sh
dataset=data/processed/v1.1
run=yolov5s-640-v1.1-main-s0
threshold=0.4176335334777832
scratch=$(mktemp -d)
pred="$scratch/predictions-test.json"
cp "experiments/$run/evaluation/predictions-test.json.gz" "$pred.gz"
gunzip -k "$pred.gz"
uv run --no-sync python -m signa_ai.evaluate \
  --gt "$dataset/coco/test.json" --manifest "$dataset/manifest.csv" \
  --labels data/labels.yaml --pred "$pred" \
  --threshold "$threshold" --out /tmp/signa-metrics-test.json
```
