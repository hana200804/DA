# Signa AI

Dữ liệu, huấn luyện, đánh giá và dịch vụ nhận diện. Thiết kế: [Thiết kế AI](../docs/04-thiet-ke-ai.md), [Dữ liệu và gán nhãn](../docs/05-du-lieu.md).

## Cài đặt

Cần Python 3.11 và [uv](https://docs.astral.sh/uv/).

```sh
cd ai
uv venv --python 3.11
uv pip install -e '.[dev]'          # thêm train, serve khi cần
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
| `src/signa_ai/evaluate.py` | CLI COCO chung cho YOLOv5, YOLOv8 và DETR | Bộ đánh giá |
| `configs/augment.md` | Bảng đối chiếu tăng cường cho ba mô hình | Huấn luyện YOLO, DETR |
| `configs/yolov5-hyp.yaml` | Siêu tham số YOLOv5 cho lượt so sánh chính | Huấn luyện YOLO |
| `src/signa_ai/augment.py` | Tham số và pipeline Albumentations dùng chung cho ba mô hình | Huấn luyện YOLO, DETR |
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

## Đánh giá mô hình

Chạy từ `ai/` với [COCO val của bộ dữ liệu v1.1](data/README.md#bộ-dữ-liệu-v11) và dự đoán của cùng tập.

```sh
uv pip install -e '.[eval]'
uv run --no-sync python -m signa_ai.evaluate \
  --gt data/processed/v1.1/coco/val.json \
  --pred /path/to/predictions-val.json \
  --manifest data/processed/v1.1/manifest.csv \
  --out metrics.json
```

- Xuất predictions bằng `to_coco_predictions`. Mỗi bản ghi có `image_id` từ COCO GT, `category_id` theo danh mục nhãn, `bbox` theo `[x, y, w, h]` bằng pixel ảnh gốc và `score` trong `[0.001, 1]`. Dùng `--labels` nếu cần chỉ định bản sao danh mục đi kèm dữ liệu.
- mAP và AP từng mã dùng COCOeval với tối đa 100 dự đoán mỗi ảnh mỗi lớp, trung bình các lớp có GT. AP theo kích thước tính từ diện tích box ảnh gốc, với hai mốc 32² và 96² thuộc nhóm vừa.
- Precision, recall và F1 gộp các mã tại IoU ≥ 0,5, ghép cùng mã theo score giảm dần. Recall từng mã và recall gộp nhóm cấm (NFR-06) dùng điểm vận hành này. Trên val lấy ngưỡng F1 cao nhất và ưu tiên ngưỡng lớn hơn khi hòa. Sau khi chốt cấu hình, truyền `--threshold` đã chọn khi chạy trên test hoặc tập thực địa.
- Ma trận nhầm lẫn tại điểm vận hành ghép theo score và IoU ≥ 0,5 giữa mọi mã để đếm nhầm lớp. Hàng là GT, cột là dự đoán, `background` biểu thị bỏ sót hoặc báo thừa. Danh sách 10 cặp chỉ đếm các ô nhầm giữa hai mã.
- Tách ngày, đêm và mưa theo `images[].tags` trong GT hoặc theo cột `tags` của `--manifest` (khớp `path` với `file_name`). Hỗ trợ `day`, `night`, `rain` và `ngày`, `đêm`, `mưa`. Dùng dấu chấm phẩy để tách nhiều thẻ trong cùng chuỗi. Điều kiện thiếu thẻ và chỉ số AP hoặc recall thiếu GT có giá trị `null`.
- Các trường `cost` để `null` cho đến khi thí nghiệm cung cấp số đo.

## Lượt so sánh chính YOLOv5, YOLOv8 · v1.1

Hai notebook, mỗi notebook một runtime Colab riêng vì repo YOLOv5 gốc cần `ultralytics>=8.4`,
còn YOLOv8 ghim 8.3.203:

| Notebook | Mô hình | Run trên Drive |
| --- | --- | --- |
| [`train_yolov8_main_v1.1.ipynb`](notebooks/train_yolov8_main_v1.1.ipynb) | YOLOv8 n, s | `yolov8{n,s}-640-v1.1-main-s0` |
| [`train_yolov5_main_v1.1.ipynb`](notebooks/train_yolov5_main_v1.1.ipynb) | YOLOv5 n, s (repo gốc, anchor) | `yolov5{n,s}-640-v1.1-main-s0` |

Cả hai theo [giao thức huấn luyện](../docs/04-thiet-ke-ai.md#3-giao-thức-huấn-luyện) và
[bảng tăng cường chung](configs/augment.md): ảnh 640, tối đa 200 epoch, patience 30, seed 0,
chọn `best.pt` theo mAP@0.5:0.95 trên val. Chọn GPU rồi **Run all**. Mất phiên thì **Run all** lại:
run xong được bỏ qua, run dở tiếp tục từ `last.pt`. Mỗi run kết thúc bằng dự đoán val và
`metrics.json` của bộ đánh giá chung. Notebook không xuất test.

## Huấn luyện YOLOv8n trên Colab · v1.1

Mở [notebook](notebooks/train_yolov8n_v1.1.ipynb) bằng **Upload notebook** tại
[Google Colab](https://colab.research.google.com/), chọn **T4 GPU** hoặc **L4 GPU**.
Notebook đã chạy đủ 100 epoch trên **NVIDIA L4**; vẫn chạy được trên T4.
Hồ sơ thật từ Drive nằm tại [experiment](experiments/yolov8n-640-v1.1-temp-s0/):
epoch tốt nhất 71, mAP50–95 val Ultralytics **tham khảo** 0,70177,
phiên train dùng 1,320266 giờ GPU (không gồm smoke/xuất dự đoán).
Notebook lấy helper/danh mục từ commit `ef46f19` đã có trên main; không cần push nhánh này.

1. Tải `signa-v1.1.zip` lên thư mục gốc **My Drive**. Bản cục bộ hiện ở
   `/home/hau/orca/workspaces/DA/AI-01-Dữ-liệu-và-nhãn/ai/data/processed/signa-v1.1.zip`.
   Notebook mount Drive, giải nén vào `/content/v1.1`, sửa `path` trong `data.yaml`,
   kiểm tra manifest v1.1 và 46 lớp theo `data/labels.yaml`.
2. Chạy ô 1–4 và xem `train_batch0.jpg` của smoke một epoch để kiểm tra box/mã.
   Chạy ô 5 để train từ COCO `yolov8n.pt`: `imgsz=640`, `batch=16`, 100 epoch,
   `patience=30`, seed 0; chọn `best.pt` theo mAP@0.5:0.95 trên val.
   Không vượt trần 200 epoch. Theo dõi điểm Colab vì 200 điểm là ngân sách cả dự án;
   thời gian/điểm sử dụng phụ thuộc loại GPU. Nếu OOM, giảm batch xuống 8.
3. Kết quả ghi trực tiếp vào
   `My Drive/Signa/experiments/yolov8n-640-v1.1-temp-s0/`: `weights/best.pt`,
   `weights/last.pt`, `results.csv`, `results.png`, `args.yaml`, phiên bản thư viện,
   `meta.json` ghi thời gian GPU từng phiên và `best-selection.json` ghi epoch/điểm chọn.
   Nếu mất phiên, chạy lại ô 1–3 rồi **ô 6 resume**, bỏ qua ô 4–5.
   Resume chỉ hoàn thành tổng epoch ban đầu; run đã hoàn tất không thể resume.
4. Ô 7 tạo `handoff/` gồm trọng số, danh mục và thẻ mô hình tạm cho backend/app qua API.
   Chưa có TFLite hay kiểm chứng FPS trên điện thoại; đây chưa phải mô hình camera offline.
   Đây cũng chưa phải lượt so sánh chính thức với YOLOv5 và DETR:
   lượt này dùng tăng cường mặc định, khác [bảng tăng cường chung](configs/augment.md).
5. Ô 8 xuất `suspected-labels-train.csv`: ảnh, số thứ tự box v1.1 từ 1,
   mã gốc, mã dự đoán, confidence và lý do. Ghép một–một theo IoU ≥ 0,5;
   khác lớp với confidence ≥ 0,8 hoặc không phát hiện ở ngưỡng 0,25 thì gắn cờ.
   Rà tay các dòng này; vẫn chọn 10% ảnh để hai người gán nhãn độc lập theo
   [quy trình chất lượng](../docs/05-du-lieu.md#kiểm-soát-chất-lượng),
   gồm cả ảnh không bị gắn cờ. Không tự sửa nhãn từ dự đoán mô hình.
6. Ô cuối xuất `evaluation/predictions-val.json`; sau khi chốt trên val,
   đổi `EXPORT_TEST=True` và chạy lại để xuất thêm `predictions-test.json`.
   Gửi cả `evaluation/` cho nhánh `ai/3-evaluation`: có nhãn chuẩn COCO, cấu hình xuất
   và danh mục. `image_id` lấy từ COCO của từng tập, `category_id` giữ nguyên ID 0–45,
   box theo pixel ảnh gốc, confidence 0,001; không dùng test để chọn cấu hình.

Dừng phiên Colab khi đã lưu và kiểm tra đủ kết quả trên Drive. Mất phiên giữa epoch
cần chạy lại epoch đó; Drive bị ngắt khi đang ghi checkpoint vẫn có thể làm hỏng lần lưu cuối.
Bộ đếm patience không được phục hồi đầy đủ qua resume nên có thể dừng muộn hơn.
Trọng số, ảnh/đồ thị, gói `handoff/`, CSV rà nhãn và dự đoán COCO val/test giữ trên Drive;
Git chỉ lưu cấu hình, meta, `results.csv` và `best-selection.json` của lượt này.
