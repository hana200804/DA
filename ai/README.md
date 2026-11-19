# Signa AI

Dữ liệu, huấn luyện, đánh giá và dịch vụ nhận diện. Thiết kế: [Thiết kế AI](../docs/04-thiet-ke-ai.md), [Dữ liệu và gán nhãn](../docs/05-du-lieu.md).

## Cài đặt

Cần Python 3.11 và [uv](https://docs.astral.sh/uv/).

```sh
cd ai
uv venv --python 3.11
uv pip install -e '.[dev]'          # thêm eval, train, serve khi cần
uv run --no-sync pytest
uv run --no-sync ruff check .
```

## Cấu trúc

| Đường dẫn | Nội dung | Nhánh phụ trách |
| --- | --- | --- |
| `data/labels.yaml` | Danh mục nhãn v1 gồm 46 lớp, nguồn duy nhất | Dữ liệu và nhãn |
| [`data/`](data/README.md) | Cấu trúc thư mục dữ liệu, bộ chạy thử v0.1, nguồn công khai và mẫu thu thực địa | Dữ liệu và nhãn |
| `data/manifests/` | Manifest `vX.Y.csv` của từng phiên bản dữ liệu | Dữ liệu và nhãn |
| `src/signa_ai/labels.py` | Đọc và kiểm tra `labels.yaml` | Khung chung |
| `src/signa_ai/detection.py` | `Detection`, interface `Detector`, xuất dự đoán COCO | Khung chung |
| `src/signa_ai/dataset.py` | Chuyển YOLO ↔ COCO, chia tập, sinh manifest | Dữ liệu và nhãn |
| `src/signa_ai/evaluate.py` | Bộ đánh giá chung bằng `pycocotools` | Bộ đánh giá |
| `configs/augment.md` | Bảng đối chiếu tăng cường cho ba mô hình | Huấn luyện YOLO, DETR |
| `experiments/` | Cấu hình, log, chỉ số của từng thí nghiệm | Huấn luyện YOLO, DETR |
| `service/` | Dịch vụ nhận diện FastAPI | Dịch vụ nhận diện |

Ảnh, video và trọng số không đưa vào Git.

## Chạy dịch vụ nhận diện

Lấy `best.pt` và `model_card.json` từ `My Drive/Signa/experiments/yolov8n-640-v1.1-temp-s0/handoff/`
và đặt cạnh nhau trong `ai/models/`. Dịch vụ đối chiếu lớp trong checkpoint với
`ai/data/labels.yaml` v1 và dùng phiên bản bàn giao `yolov8n-v1.1-temp`.

Ví dụ chạy trên CPU từ thư mục gốc của repo.

```sh
cd ai
uv venv --python 3.11
uv pip install --index https://download.pytorch.org/whl/cpu torch torchvision
uv pip install -e '.[serve]'
SIGNA_YOLOV8_WEIGHTS=models/best.pt SIGNA_DEVICE=cpu \
  uv run --no-sync uvicorn service.app:app --host 127.0.0.1 --port 8000
```

`SIGNA_YOLOV8_WEIGHTS` trỏ đến checkpoint `.pt` cục bộ. Dịch vụ chọn CUDA nếu
PyTorch hỗ trợ CUDA và GPU khả dụng, ngược lại dùng CPU. Dùng `SIGNA_DEVICE`
để chọn thiết bị cụ thể, ví dụ `cpu` hoặc `cuda:0`.

```sh
curl -i http://127.0.0.1:8000/healthz
curl http://127.0.0.1:8000/v1/models
curl --fail-with-body --max-time 5 \
  -F model=yolov8 -F 'image=@/duong-dan/anh.jpg;type=image/jpeg' \
  http://127.0.0.1:8000/v1/detect
```

- `POST /v1/detect` nhận multipart với `image`, `model` và hai trường tuỳ chọn
  `conf`, `iou` trong khoảng [0, 1]. Mặc định của YOLOv8n tạm là `conf=0.40`
  (làm tròn từ ngưỡng F1 tối đa 0,401663 trên val v1.1) và `iou=0.7`.
  Khi request bỏ qua `conf`, dịch vụ ưu tiên `operating_point.threshold` trong
  `model_card.json`, sau đó đến biến `SIGNA_YOLOV8_CONF`, rồi giá trị mặc định.
  Ảnh JPEG, PNG hoặc WebP có giới hạn 10 MB và 25 triệu pixel. Dịch vụ xử lý ảnh trong bộ nhớ.
- Kết quả gồm `modelName`, `modelVersion`, `labelMapVersion`, `inferenceMs` và
  `detections`. Mỗi phát hiện có `signTypeCode`, `confidence` và `box`
  với `x`, `y`, `width`, `height` theo pixel ảnh gốc.
- `GET /v1/models` trả `models` gồm các mô hình đã nạp cùng `modelCard` và
  `defaultConf` là ngưỡng đang dùng khi request bỏ qua `conf`.
  Dịch vụ vẫn khởi động khi thiếu trọng số. `GET /healthz` báo các mô hình
  còn thiếu bằng HTTP `503` và chỉ trả `200` khi đủ ba mô hình.
  Bản tạm vẫn nhận yêu cầu `model=yolov8` khi YOLOv8 đã nạp.
- Yêu cầu chọn mô hình chưa nạp nhận `503 RECOGNITION_UNAVAILABLE`.
  Khi hàng chờ đầy, API trả cùng mã lỗi với `Retry-After: 1`.

Backend gọi dịch vụ trong mạng nội bộ. Xem [hợp đồng API nội bộ](../docs/07-api.md#7-dịch-vụ-nhận-diện-nội-bộ)
và OpenAPI ở `/openapi.json` của dịch vụ.
