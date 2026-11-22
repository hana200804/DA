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

### Ba mô hình

Cấu hình đề xuất cho server gồm YOLOv5s, YOLOv8s và DETR-R50. YOLOv8s có
mAP@0.5:0.95 cao nhất trên val v1.1 trong [lượt so sánh chính](experiments/main-v1.1.md).
Thời gian trên server tham chiếu chưa đo. Lựa chọn AI-04 đang chờ chốt.

Lấy checkpoint của nhóm từ My Drive và đặt mỗi trọng số trong một thư mục riêng.

| Mô hình | Nguồn trên My Drive | Tệp cục bộ trong `ai/` |
| --- | --- | --- |
| YOLOv5s | `Signa/experiments/yolov5s-640-v1.1-main-s0/weights/best.pt` | `models/yolov5s/best.pt` |
| YOLOv8s | `Signa/experiments/yolov8s-640-v1.1-main-s0/weights/best.pt` | `models/yolov8s/best.pt` |
| DETR-R50 | `Signa/experiments/detr-r50-640-v1.1-main-s0/best.pt` | `models/detr-r50/best.pt` |

Đặt `model_card.json` bên cạnh từng `best.pt`. Thẻ cần tên họ mô hình
(`yolov5`, `yolov8` hoặc `detr`), phiên bản duy nhất và phiên bản danh mục nhãn khớp
`ai/data/labels.yaml`. Ví dụ các trường dùng để nạp YOLOv8s và đặt ngưỡng val đã làm tròn.

```json
{
  "modelName": "yolov8",
  "modelVersion": "yolov8s-v1.1",
  "labelMapVersion": 1,
  "operating_point": {"threshold": 0.53}
}
```

Nội dung khác của [thẻ mô hình](../docs/04-thiet-ke-ai.md#8-phiên-bản-và-thẻ-mô-hình)
được trả nguyên vẹn qua API. Chỉ cấu hình checkpoint và checkout đáng tin cậy của nhóm.

Ví dụ cài đặt và chạy trên máy CPU. Trên server CUDA, cài bản PyTorch tương ứng
và bỏ `SIGNA_DEVICE=cpu` để dịch vụ tự chọn thiết bị.

```sh
cd ai
uv venv --python 3.11
uv pip install --index https://download.pytorch.org/whl/cpu torch torchvision
uv pip install -e '.[serve-all]'
git clone https://github.com/ultralytics/yolov5.git models/yolov5-source
git -C models/yolov5-source checkout --detach 1e7cf74cb64d719b34f7315aa13eab9e32cde672
SIGNA_YOLOV5_REPO=models/yolov5-source \
  SIGNA_YOLOV5_WEIGHTS=models/yolov5s/best.pt \
  SIGNA_YOLOV8_WEIGHTS=models/yolov8s/best.pt \
  SIGNA_DETR_WEIGHTS=models/detr-r50/best.pt \
  SIGNA_DEVICE=cpu \
  uv run --no-sync uvicorn service.app:app --host 127.0.0.1 --port 8000
```

`serve-all` dùng bản `ultralytics` mà checkout YOLOv5 yêu cầu. Dùng môi trường ảo
riêng cho `train` hoặc `serve`, vì hai nhóm đó ghim phiên bản `ultralytics` khác.

| Biến môi trường | Ý nghĩa |
| --- | --- |
| `SIGNA_YOLOV5_WEIGHTS`, `SIGNA_YOLOV8_WEIGHTS`, `SIGNA_DETR_WEIGHTS` | Đường dẫn đến từng checkpoint `.pt` cục bộ |
| `SIGNA_YOLOV5_REPO` | Đường dẫn đến checkout YOLOv5 gốc ở commit trên |
| `SIGNA_DEVICE` | `cpu` hoặc thiết bị CUDA như `cuda:0`. Tự chọn `cuda:0` nếu khả dụng, nếu không thì dùng CPU |
| `SIGNA_DEFAULT_MODEL` | Tên họ hoặc `modelVersion` của mô hình mặc định. Khi chưa đặt thì dùng `yolov8` |
| `SIGNA_YOLOV5_CONF`, `SIGNA_YOLOV8_CONF`, `SIGNA_DETR_CONF` | Ngưỡng khi request và thẻ mô hình đều thiếu ngưỡng. Giá trị dự phòng là 0,42, 0,40 và 0,71 tương ứng |

### Phát triển với một mô hình

Để giữ môi trường YOLOv8 tạm, cài `.[serve]` thay cho `.[serve-all]`. Dùng
`best.pt` và `model_card.json` trong `My Drive/Signa/experiments/yolov8n-640-v1.1-temp-s0/handoff/`
với phiên bản `yolov8n-v1.1-temp`. Chỉ đặt `SIGNA_YOLOV8_WEIGHTS` và chạy Uvicorn
như trên. API nhận diện dùng được với mô hình đã nạp. `/healthz` vẫn trả `503`
khi thiếu một trong ba mô hình.

### Gọi API

```sh
curl -i http://127.0.0.1:8000/healthz
curl http://127.0.0.1:8000/v1/models
curl --fail-with-body --max-time 5 \
  -F model=yolov8 -F 'image=@/duong-dan/anh.jpg;type=image/jpeg' \
  http://127.0.0.1:8000/v1/detect
```

- `POST /v1/detect` nhận multipart với `image` và các trường tuỳ chọn
  `model`, `conf`, `iou`. Chọn `yolov5`, `yolov8`, `detr` hoặc phiên bản đã
  nạp ghi trong `modelVersion`. Bỏ trường `model` để dùng mô hình mặc định.
  `conf` và `iou` nằm trong khoảng [0, 1]. Nếu thiếu `conf`, dịch vụ ưu tiên
  `operating_point.threshold` rồi dùng cấu hình ngưỡng ở bảng trên. Hai họ YOLO
  dùng ngưỡng IoU mặc định 0,7. DETR bỏ qua `iou`.
  Ảnh JPEG, PNG hoặc WebP có giới hạn 10 MB và 25 triệu pixel.
  Dịch vụ xử lý ảnh trong bộ nhớ.
- Kết quả gồm `modelName`, `modelVersion`, `labelMapVersion`, `inferenceMs` và
  `detections`. Mỗi phát hiện có `signTypeCode`, `confidence` và `box`
  với `x`, `y`, `width`, `height` theo pixel ảnh gốc.
- `GET /v1/models` trả `models` gồm các mô hình đã nạp cùng `modelCard` và
  `defaultConf` là ngưỡng đang dùng khi request bỏ qua `conf`.
  `GET /healthz` báo các mô hình còn thiếu bằng HTTP `503` và chỉ trả `200`
  khi đủ ba mô hình.
- API trả `400` nếu giá trị `model` không hợp lệ hoặc không khớp phiên bản đã nạp.
  Nếu họ mô hình được chọn hoặc mô hình mặc định chưa nạp, API trả
  `503 RECOGNITION_UNAVAILABLE`.
- Mỗi họ YOLO có tối đa 4 yêu cầu giữ semaphore. DETR có 1. Mỗi họ có thêm
  tối đa 8 yêu cầu chờ. Khi đầy, API trả `503` kèm `Retry-After: 1`.

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
