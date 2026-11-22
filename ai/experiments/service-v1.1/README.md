# Kiểm chứng dịch vụ nhận diện bằng checkpoint v1.1

FastAPI đối chiếu SHA-256 trước khi nạp checkpoint YOLOv5s, YOLOv8s và DETR-R50.
`/healthz` trả `200`. `/v1/models` trả đúng phiên bản, danh mục nhãn và ngưỡng trong thẻ.
Benchmark kiểm tra mã `200`, phiên bản, kiểu dữ liệu, mã biển, ngưỡng tin cậy và biên box trong
từng phản hồi `/v1/detect`. [Kết quả HTTP và SHA-256](http-results.json)

| Mô hình | p50 HTTP (ms) | p95 HTTP (ms) | Trung bình HTTP (ms) | Ảnh có phát hiện / 200 |
| --- | ---: | ---: | ---: | ---: |
| YOLOv5s | 102,3 | 201,8 | 120,8 | 185 |
| YOLOv8s | 135,2 | 179,9 | 141,4 | 173 |
| DETR-R50 | 692,5 | 898,6 | 727,5 | 170 |

Một worker Uvicorn chạy trên laptop Core i5-11400H, với 4 lõi vật lý, PyTorch CPU 4 luồng và
OpenCV 1 luồng. Máy có 15 GiB RAM theo hệ điều hành. Client chạy trên hai lõi riêng qua HTTP
loopback, một yêu cầu tại một thời điểm. Mỗi mô hình chạy 10 yêu cầu làm nóng, sau đó đo
200 ảnh đầu của `coco/val.json`. Trước lượt đo YOLOv8 có thêm một yêu cầu bỏ trường `model`
để kiểm tra mô hình mặc định.

`client_ms` tính từ lúc chuẩn bị gửi multipart đến khi nhận đủ thân phản hồi.
`inferenceMs` do dịch vụ đo sau khi giải mã ảnh. Script tính p50 và p95 từ đúng 600 mẫu
đã lưu. Mẫu chứa tên họ mô hình, ID ảnh, mã HTTP, hai thời gian và số box.
[Mẫu từng yêu cầu](http-results-samples.json.gz), [Log đo](benchmark.log.gz), [Script đo](../../scripts/benchmark_service.py)

Các số trên là kết quả HTTP loopback của laptop. Q7 vẫn cần đo NFR-02 qua backend trên
server demo 4 vCPU, 8 GB RAM, không GPU, theo tải trong kịch bản kiểm thử.
[NFR-02](../../../docs/02-dac-ta-yeu-cau.md#9-yêu-cầu-phi-chức-năng), [Kịch bản server demo](../../../docs/08-kiem-thu.md#8-kiểm-thử-hiệu-năng-server)

## Đối chiếu hai phiên bản Ultralytics cho YOLOv8s

Dùng cùng `best.pt`, GPU RTX 3050 và 1.599 ảnh val qua adapter dịch vụ cho cả hai phiên bản. Giải mã ảnh bằng PIL và xuất ở confidence 0,001, IoU NMS 0,7, ảnh 640. Precision và recall tính tại cùng ngưỡng 0,5279324054718018.

| Đường xuất và phiên bản | mAP@0.5 | mAP@0.5:0.95 | Precision | Recall | Số box dự đoán |
| --- | ---: | ---: | ---: | ---: | ---: |
| Notebook val gốc · 8.3.203 | 84,0340% | 69,5631% | 90,31% | 80,22% | 4.413 |
| Adapter · 8.3.203 | 84,0345% | 69,5445% | 90,31% | 80,19% | 4.413 |
| Adapter · 8.4.201 | 84,0346% | 69,5394% | 90,31% | 80,19% | 4.412 |

Chênh lệch mAP@0.5:0.95 giữa hai adapter là 0,0051 điểm phần trăm. Có 5 ảnh có số box khác
nhau. Dịch vụ giữ ngưỡng từ val gốc. Hai tệp COCO đã lưu có thể chấm lại bằng CPU.
[Dự đoán 8.3.203](predictions-val-8.3.203.json.gz), [Dự đoán 8.4.201](predictions-val-8.4.201.json.gz), [Chỉ số và SHA-256](adapter-val-parity.json), [Xuất lại bằng hai môi trường riêng](../../README.md#hạn-chế-đã-biết-phiên-bản-ultralytics-của-yolov8)

Từ `ai/`, giải nén và chấm từng tệp với cùng GT và manifest val. Cài `.[eval]` trong môi trường
`.venv` để dùng bộ chấm chung. Bỏ `--threshold` nếu cần tính lại F1 tối đa.

```sh
for version in 8.3.203 8.4.201; do
  pred="experiments/service-v1.1/predictions-val-${version}.json"
  gunzip -k "${pred}.gz"
  CUDA_VISIBLE_DEVICES='' .venv/bin/python -m signa_ai.evaluate \
    --gt data/processed/v1.1/coco/val.json \
    --pred "$pred" --manifest data/processed/v1.1/manifest.csv \
    --threshold 0.5279324054718018 --out "/tmp/metrics-val-${version}-fixed.json"
done
```

## Tái tạo phép đo HTTP

Lấy ba checkpoint và thẻ tương ứng. Dịch vụ tự xác thực hash mỗi checkpoint khi khởi động.
Phép đo dùng Python 3.11.18, torch 2.15.0+cpu, torchvision 0.29.2+cpu, ultralytics 8.4.201,
transformers 4.57.6, timm 1.0.24, FastAPI 0.165.0, Starlette 1.10.0, Uvicorn 0.54.0,
OpenCV 5.1.0.95, HTTPX 0.28.1, NumPy 2.4.6 và Pillow 13.0.0.
[Hướng dẫn dịch vụ](../../README.md#chạy-dịch-vụ-nhận-diện)

Đặt các biến môi trường checkpoint rồi chạy từ `ai/` với `serve-all` và `httpx` đã cài.
Xem topology bằng `lscpu -e=CPU,CORE,SOCKET`. Trên máy đo, CPU 0–3 và 4–5 thuộc sáu
lõi vật lý riêng. Nếu topology khác, chỉnh hai danh sách CPU theo máy đo.

```sh
CUDA_VISIBLE_DEVICES='' SIGNA_DEVICE=cpu OMP_NUM_THREADS=4 \
  MKL_NUM_THREADS=4 OPENBLAS_NUM_THREADS=4 \
  taskset -c 0-3 .venv/bin/python -c '
import cv2, torch, uvicorn
torch.set_num_threads(4)
torch.set_num_interop_threads(1)
cv2.setNumThreads(1)
uvicorn.run("service.app:app", host="127.0.0.1", port=18743, workers=1, access_log=False)
'
```

Chạy client trong terminal khác trên cùng máy sau khi dịch vụ nạp xong.

```sh
CUDA_VISIBLE_DEVICES='' taskset -c 4-5 .venv/bin/python scripts/benchmark_service.py \
  --port 18743 --dataset data/processed/v1.1 --out /tmp/http-results.json \
  > /tmp/benchmark.log
gzip -n -9 -k /tmp/http-results-samples.json /tmp/benchmark.log
```

Để đọc mẫu và log của lần đã lưu, giải nén từ `ai/`.

```sh
gunzip -k experiments/service-v1.1/http-results-samples.json.gz
gunzip -k experiments/service-v1.1/benchmark.log.gz
```
