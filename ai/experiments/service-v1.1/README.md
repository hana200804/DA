# Kiểm chứng dịch vụ nhận diện bằng checkpoint v1.1

FastAPI nạp YOLOv5s, YOLOv8s và DETR-R50 với ba file `best.pt` đã đối chiếu SHA-256. `/healthz` trả `200`, `/v1/models` công bố đủ phiên bản, danh mục nhãn và ngưỡng trong thẻ. `/v1/detect` chạy 600 yêu cầu hợp lệ và một yêu cầu kiểm tra mô hình mặc định YOLOv8. Phản hồi giữ đúng phiên bản, mã biển, ngưỡng tin cậy và box trong biên ảnh. [Kết quả HTTP và hash checkpoint](http-results.json)

| Mô hình | p50 HTTP (ms) | p95 HTTP (ms) | Trung bình HTTP (ms) | Ảnh có phát hiện / 200 |
| --- | ---: | ---: | ---: | ---: |
| YOLOv5s | 96,0 | 113,0 | 99,1 | 185 |
| YOLOv8s | 133,1 | 145,1 | 134,5 | 173 |
| DETR-R50 | 672,6 | 768,5 | 685,2 | 170 |

Một worker Uvicorn trên laptop Core i5-11400H, ép tiến trình dịch vụ vào 4 lõi vật lý, dùng PyTorch CPU 4 luồng và OpenCV 1 luồng. Máy có 15 GiB RAM theo hệ điều hành. Client chạy trên hai lõi khác qua loopback HTTP. Mỗi mô hình dùng 200 ảnh đầu của `coco/val.json`, một request tại một thời điểm và khởi động 10 ảnh. Bắt đầu đo từ trước khi gửi multipart đến khi nhận đủ thân phản hồi. `inferenceMs` trong JSON theo đồng hồ của dịch vụ và bắt đầu sau khi giải mã ảnh.

Các p95 trên laptop thấp hơn các mốc 1 s và 3 s. Q7 vẫn cần đo trên máy demo 4 vCPU, 8 GB RAM, không GPU. Phép đo này chưa có tải đồng thời và chưa đi qua backend. [NFR-02](../../../docs/02-dac-ta-yeu-cau.md#9-yêu-cầu-phi-chức-năng), [Server demo tham chiếu](../../../docs/08-kiem-thu.md#3-đánh-giá-và-so-sánh-mô-hình)

## Đối chiếu hai phiên bản Ultralytics cho YOLOv8s

Dùng cùng `best.pt`, GPU RTX 3050 và 1.599 ảnh val qua adapter dịch vụ cho cả hai phiên bản. Giải mã ảnh bằng PIL và xuất ở confidence 0,001, IoU NMS 0,7, ảnh 640. Precision và recall tính tại cùng ngưỡng 0,5279324054718018.

| Đường xuất và phiên bản | mAP@0.5 | mAP@0.5:0.95 | Precision | Recall | Số box dự đoán |
| --- | ---: | ---: | ---: | ---: | ---: |
| Notebook val gốc · 8.3.203 | 84,0340% | 69,5631% | 90,31% | 80,22% | 4.413 |
| Adapter · 8.3.203 | 84,0345% | 69,5445% | 90,31% | 80,19% | 4.413 |
| Adapter · 8.4.201 | 84,0346% | 69,5394% | 90,31% | 80,19% | 4.412 |

Chênh lệch mAP@0.5:0.95 giữa hai adapter là 0,0051 điểm phần trăm. Có 5 ảnh có số box khác nhau, nên kết quả này xác nhận độ gần nhau của chỉ số gộp trong môi trường đã đo. Giữ ngưỡng từ val gốc khi nạp dịch vụ. Dùng quy trình xuất dự đoán val trong hướng dẫn dịch vụ với hai môi trường riêng để tái tạo, rồi chấm một lượt với `--threshold 0.5279324054718018` và một lượt để tính F1 tối đa. [Chỉ số và SHA-256](adapter-val-parity.json), [Quy trình xuất qua adapter](../../README.md#hạn-chế-đã-biết-phiên-bản-ultralytics-của-yolov8)

## Tái tạo phép đo HTTP

Lấy đúng ba checkpoint và thẻ theo [hướng dẫn dịch vụ](../../README.md#chạy-dịch-vụ-nhận-diện). Chạy `sha256sum` với mỗi `best.pt` và đối chiếu `weights_sha256` trong [kết quả](http-results.json). Khởi động Uvicorn với `SIGNA_DEVICE=cpu`, một worker và gói `serve-all`. Phép đo này dùng Python 3.11.18, torch 2.15.0+cpu, torchvision 0.29.2+cpu, ultralytics 8.4.201, transformers 4.57.6, timm 1.0.24, FastAPI 0.165.0, Starlette 1.10.0, Uvicorn 0.54.0, OpenCV 5.1.0.95, HTTPX 0.28.1, NumPy 2.4.6 và Pillow 13.0.0.

Trên máy Linux, có thể ghim luồng bằng `taskset`. Đặt các biến cấu hình checkpoint theo README, rồi chạy từ `ai/` với `serve-all` và `httpx` đã cài. Chọn bốn lõi vật lý phù hợp với máy đo.

```sh
CUDA_VISIBLE_DEVICES='' SIGNA_DEVICE=cpu OMP_NUM_THREADS=4 \
  MKL_NUM_THREADS=4 OPENBLAS_NUM_THREADS=4 \
  taskset -c 0-3 .venv/bin/python -c '
import cv2, torch, uvicorn
torch.set_num_threads(4)
torch.set_num_interop_threads(1)
cv2.setNumThreads(1)
uvicorn.run("service.app:app", host="127.0.0.1", port=8000, access_log=False)
'
```

Chạy client trong terminal khác trên cùng máy sau khi dịch vụ nạp xong:

```sh
.venv/bin/python scripts/benchmark_service.py \
  --dataset data/processed/v1.1 --out /tmp/signa-http-results.json
```
