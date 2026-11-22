# Signa AI

Dữ liệu, huấn luyện, đánh giá và dịch vụ nhận diện. Thiết kế: [Thiết kế AI](../docs/04-thiet-ke-ai.md), [Dữ liệu và gán nhãn](../docs/05-du-lieu.md).

## Cài đặt

Cần Python 3.11 và [uv](https://docs.astral.sh/uv/).

```sh
cd ai
uv venv --python 3.11
uv pip install -e '.[dev]'          # thêm detr, train, serve khi cần
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
| `src/signa_ai/mobile.py` | Letterbox, giải mã TFLite, NMS và sinh `labels.json` | Mô hình điện thoại |
| `notebooks/export_tflite*.ipynb`, `scripts/export_tflite.py` | Xuất và chấm các ứng viên TFLite trên Colab | Mô hình điện thoại |
| `configs/augment.md` | Bảng đối chiếu tăng cường cho ba mô hình | Huấn luyện YOLO, DETR |
| `configs/yolov5-hyp.yaml` | Siêu tham số YOLOv5 cho lượt so sánh chính | Huấn luyện YOLO |
| `configs/detr-r50-config.json` | Cấu hình kiến trúc DETR-R50 tại revision đã ghim, dùng khi nạp checkpoint | Huấn luyện và suy luận DETR |
| `src/signa_ai/augment.py` | Tham số và pipeline Albumentations dùng chung cho ba mô hình | Huấn luyện YOLO, DETR |
| `src/signa_ai/detr.py` | Dataset COCO, nạp checkpoint và suy luận DETR-R50 | Huấn luyện và suy luận DETR |
| [`notebooks/train_detr_main_v1.1.ipynb`](notebooks/train_detr_main_v1.1.ipynb) | Huấn luyện và chấm DETR-R50 trên val v1.1 | Huấn luyện DETR |
| [`notebooks/predict_test_v1.1.ipynb`](notebooks/predict_test_v1.1.ipynb) | Kiểm tái tạo val, chấm test và đo thời gian 5 lượt chính | Bộ đánh giá |
| `experiments/` | Cấu hình, log, chỉ số của từng thí nghiệm | Huấn luyện YOLO, DETR |
| `service/` | Dịch vụ nhận diện FastAPI | Dịch vụ nhận diện |

Ảnh, video và trọng số không đưa vào Git.

## Huấn luyện DETR-R50 trên Colab

1. Mở [notebook DETR](notebooks/train_detr_main_v1.1.ipynb) trong Colab, chọn GPU **L4, A100 hoặc H100** (ưu tiên A100 nếu có) và đặt `signa-v1.1.zip` ở thư mục gốc My Drive.
2. Đặt `SMOKE = True` ở ô đầu và chọn **Run all** để chạy một epoch với khoảng 2% của train và val. Kết quả thử nằm trong `/content/signa-smoke/`.
3. Đặt `SMOKE = False` rồi **Run all** cho lượt chính. Notebook dò batch, lưu checkpoint theo epoch ở `MyDrive/Signa/experiments/detr-r50-640-v1.1-main-s0/` và chấm val bằng bộ đánh giá chung.

Khi phiên Colab ngắt, chọn GPU được hỗ trợ và **Run all** để tiếp tục từ `last.pt`. Batch giữ nguyên qua các phiên. GPU của phiên mới cần đủ VRAM cho batch đã lưu. Chạy một runtime cho mỗi lượt. Sau khi có `done.json`, notebook bỏ qua lượt đã hoàn thành. Tập test dành cho đánh giá sau khi chốt cấu hình.

Hồ sơ lượt chính nằm trong [thư mục thí nghiệm](experiments/detr-r50-640-v1.1-main-s0/). Xem [bảng so sánh v1.1](experiments/main-v1.1.md) để đối chiếu kết quả val.

Khi cần suy luận, cài `uv pip install -e '.[detr]'` rồi dùng `load_detr_checkpoint` trong [`signa_ai.detr`](src/signa_ai/detr.py) với `best.pt` và danh mục nhãn đã dùng khi huấn luyện. Hàm không cần mạng. Cấu hình kiến trúc đọc từ [`configs/detr-r50-config.json`](configs/detr-r50-config.json), trọng số đọc từ checkpoint cục bộ. Tệp cấu hình là `config.json` của `facebook/detr-resnet-50` tại revision `1d5f47bd` đã ghim trong notebook, chép nguyên văn (SHA-256 `e7bcf399…17e6`). Hàm ghi đè số lớp, nhãn, `use_pretrained_backbone=False` và `auxiliary_loss=True` như lúc huấn luyện. Truyền `config=` để dùng tệp cấu hình khác. `predict_detr` nhận ảnh PIL, chạy trên thiết bị của mô hình và trả phát hiện theo pixel ảnh gốc. Dùng `to_coco_predictions` cùng danh mục để lấy `category_id`, `bbox` và `score` cho bộ đánh giá.

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
`ai/data/labels.yaml`. Dịch vụ đối chiếu SHA-256 của checkpoint với `weightsSha256`
trước khi nạp. Nếu thiếu hash hoặc hash không khớp, dịch vụ từ chối khởi động.
Dịch vụ đọc ngưỡng mặc định từ `operating_point.threshold`.

Ba thẻ của lượt chính có trong `experiments/`. Ngưỡng là ngưỡng F1 tối đa trên val, lấy từ
`metrics.json` của từng lượt. [Kiểm chứng HTTP với trọng số thật](experiments/service-v1.1/README.md).

| Mô hình | Thẻ | `modelVersion` | Ngưỡng |
| --- | --- | --- | --- |
| YOLOv5s | `experiments/yolov5s-640-v1.1-main-s0/model_card.json` | `yolov5s-v1.0` | 0,4176 |
| YOLOv8s | `experiments/yolov8s-640-v1.1-main-s0/model_card.json` | `yolov8s-v1.0` | 0,5279 |
| DETR-R50 | `experiments/detr-r50-640-v1.1-main-s0/model_card.json` | `detr-r50-v1.0` | 0,7065 |

Chép mỗi thẻ thành `models/<thư mục>/model_card.json` cạnh `best.pt` tương ứng ở bảng trên.
Ví dụ thẻ cho YOLOv8s.

```json
{
  "modelName": "yolov8",
  "modelVersion": "yolov8s-v1.0",
  "labelMapVersion": 1,
  "weightsSha256": "49012dc0aa68865114f3a09545cab371978e5a52567cced52ebb050f49bf219d",
  "operating_point": {"threshold": 0.5279324054718018}
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
DETR lấy phụ thuộc từ extra `detr`, nên cùng `transformers` và `timm` với lượt đánh giá.

| Biến môi trường | Ý nghĩa |
| --- | --- |
| `SIGNA_YOLOV5_WEIGHTS`, `SIGNA_YOLOV8_WEIGHTS`, `SIGNA_DETR_WEIGHTS` | Đường dẫn đến từng checkpoint `.pt` cục bộ |
| `SIGNA_YOLOV5_REPO` | Đường dẫn đến checkout YOLOv5 gốc ở commit trên |
| `SIGNA_DEVICE` | `cpu` hoặc thiết bị CUDA như `cuda:0`. Tự chọn `cuda:0` nếu khả dụng, nếu không thì dùng CPU |
| `SIGNA_DEFAULT_MODEL` | Tên họ hoặc `modelVersion` của mô hình mặc định. Khi chưa đặt thì dùng `yolov8`. Giá trị không phải họ mô hình hay `modelVersion` đã nạp làm dịch vụ dừng ngay khi khởi động. `/v1/models` trả họ mô hình này trong `defaultModel` |
| `SIGNA_YOLOV5_CONF`, `SIGNA_YOLOV8_CONF`, `SIGNA_DETR_CONF` | Ngưỡng khi request và thẻ mô hình đều thiếu ngưỡng. Giá trị dự phòng là 0,42, 0,40 và 0,71 tương ứng |

### Cách dịch vụ xử lý ảnh

- Ảnh được giải mã một lần ở tầng dịch vụ rồi đưa cùng một mảng RGB cho cả ba mô hình.
  Nếu ảnh còn thẻ EXIF Orientation, dịch vụ xoay ảnh theo thẻ trước khi suy luận
  (`ImageOps.exif_transpose`). Box trả về tính theo pixel của ảnh đã xoay. Ảnh có cạnh
  dưới 8 px bị từ chối với `400`.
- DETR chạy qua `load_detr_checkpoint` và `predict_detr` của `signa_ai.detr`, giống lúc chọn
  ngưỡng trên val. Trên CUDA, suy luận dùng autocast fp16. API bỏ box bị cắt về diện tích 0,
  còn lúc chấm val vẫn giữ chúng.
- Mỗi họ YOLO có 4 slot, DETR có 1, cộng 8 request chờ. Vượt mức này dịch vụ trả `503` kèm
  `Retry-After: 1`. Adapter YOLO dùng khoá trong tiến trình, nên thực tế mỗi họ chạy một
  suy luận một lúc. Dịch vụ chưa có timeout phía server: request mà backend đã bỏ sau 5 giây
  vẫn giữ chỗ trong hàng chờ cho đến khi chạy xong. Thời gian chờ thực tế chưa đo.

### Hạn chế đã biết: phiên bản Ultralytics của YOLOv8

`serve-all` phục vụ YOLOv8 bằng `ultralytics==8.4.201`, vì checkout YOLOv5 gốc cần
`ultralytics>=8.4`. Lượt huấn luyện và `predictions-val.json` của YOLOv8 dùng `8.3.203`
(`experiments/yolov8s-640-v1.1-main-s0/meta.json`). Hai bản có thể khác nhau ở tiền xử lý
hoặc NMS. Trên val qua adapter, mAP@0.5:0.95 lệch 0,0051 điểm phần trăm giữa hai bản trên RTX 3050. Phiên bản và ngưỡng đánh giá chính giữ nguyên. [Số liệu kiểm tương đương](experiments/service-v1.1/README.md#đối-chiếu-hai-phiên-bản-ultralytics-cho-yolov8s)

Để xuất lại dự đoán trong môi trường GPU như lần đối chiếu gốc, tạo hai môi trường ảo riêng.
Có thể chấm trực tiếp trên CPU từ hai tệp dự đoán đã lưu. [Tệp dự đoán và cách giải nén](experiments/service-v1.1/README.md#đối-chiếu-hai-phiên-bản-ultralytics-cho-yolov8s)

1. Mở runtime Colab mới, clone repo vào `/content/signa-source` (như notebook), giải nén `signa-v1.1.zip` vào `/content/v1.1` và đặt
   `best.pt` của YOLOv8s ở `/content/yolov8s/best.pt`.
2. Cài `serve` cho `8.3.203` và `serve-all` cho `8.4.201`. Xuất dự đoán val qua adapter dịch vụ
   với `conf` 0,001, `iou` 0,7, `imgsz` 640 và `max_det` 300.

   ```sh
   cd /content/signa-source/ai
   python -m venv /content/signa-8.3.203
   /content/signa-8.3.203/bin/pip install -e '.[serve,eval]'
   python -m venv /content/signa-8.4.201
   /content/signa-8.4.201/bin/pip install -e '.[serve-all,eval]'

   for version in 8.3.203 8.4.201; do
     "/content/signa-${version}/bin/python" - "$version" <<'EOF'
   import json
   import sys
   from pathlib import Path

   from service.images import decode_image
   from service.yolov8 import YoloV8Detector
   from signa_ai.detection import to_coco_predictions
   from signa_ai.labels import load_label_map

   data = Path("/content/v1.1")
   labels = load_label_map()
   model = YoloV8Detector(Path("/content/yolov8s/best.pt"), labels, "yolov8s-v1.0", "cuda:0")
   truth = json.loads((data / "coco/val.json").read_text())
   rows = []
   for image in truth["images"]:
       with open(data / image["file_name"], "rb") as file:
           detections = model.predict(decode_image(file), conf=0.001, iou=0.7)
       rows.extend(to_coco_predictions(image["id"], detections, labels))
   Path(f"/content/predictions-val-{sys.argv[1]}.json").write_text(json.dumps(rows))
   EOF
   done
   ```

3. Chấm cả hai phiên bản trên CPU ở ngưỡng đã chốt.

   ```sh
   for version in 8.3.203 8.4.201; do
     CUDA_VISIBLE_DEVICES='' "/content/signa-${version}/bin/python" -m signa_ai.evaluate \
       --gt /content/v1.1/coco/val.json \
       --pred "/content/predictions-val-${version}.json" \
       --manifest /content/v1.1/manifest.csv \
       --threshold 0.5279324054718018 \
       --out "/content/metrics-val-${version}-fixed.json"
   done
   ```

4. So `map_50`, `map_50_95` và `operating_point` trong hai tệp metrics với
   `experiments/yolov8s-640-v1.1-main-s0/metrics.json` (bản `8.3.203`: mAP@0.5:0.95 là 0,6956).
   So thêm số dự đoán của từng ảnh với `evaluation/predictions-val.json` của lượt trên Drive.
5. Ghi kết quả vào `experiments/main-v1.1.md`. Nếu lệch rõ, phục vụ YOLOv8 bằng `8.3.203`
   trong một tiến trình riêng thay vì đổi phiên bản đã đánh giá.

Bước 2 đi qua `decode_image`, nên kết quả gồm cả chênh lệch do giải mã bằng PIL thay vì
đường dẫn ảnh như notebook.

### Phát triển với một mô hình

Để giữ môi trường YOLOv8 tạm, cài `.[serve]` thay cho `.[serve-all]`. Dùng
`best.pt` và `model_card.json` trong `My Drive/Signa/experiments/yolov8n-640-v1.1-temp-s0/handoff/`
với phiên bản `yolov8n-v1.1-temp`. Chỉ đặt `SIGNA_YOLOV8_WEIGHTS` và chạy Uvicorn
như trên. API nhận diện dùng được với mô hình đã nạp. `/healthz` vẫn trả `503`
khi thiếu một trong ba mô hình.

Với gói bàn giao cũ đã lấy từ nguồn tin cậy của nhóm, chạy `sha256sum best.pt` rồi
điền hash vào `weightsSha256` nếu thẻ chưa có trường này.

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
- Precision, recall và F1 gộp các mã tại IoU ≥ 0,5, ghép cùng mã theo score giảm dần. Recall từng mã và recall gộp nhóm cấm theo ảnh dùng cùng điểm vận hành. Trên val lấy ngưỡng F1 cao nhất và ưu tiên ngưỡng lớn hơn khi hòa. Truyền `--threshold` đã khóa khi chấm ảnh test. NFR-06 còn có phép nghiệm thu chuỗi sau tracker. [Kiểm thử thực địa](../docs/08-kiem-thu.md#5-thử-nghiệm-thực-địa)
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

Notebook YOLOv8 đọc kích thước ảnh, cỡ mô hình và batch từ biến môi trường `SIGNA_IMGSZ`
(mặc định 640), `SIGNA_SIZES` (mặc định `n,s`) và `SIGNA_BATCH` (mặc định 16).
Tên run là `yolov8{size}-{IMGSZ}-v1.1-main-s0`. Thí nghiệm AI-07 về biển nhỏ thêm
`%env SIGNA_IMGSZ=960` và `%env SIGNA_SIZES=s` vào ô `%env` đầu notebook, cạnh
`%env SIGNA_NOTEBOOK_COMMIT=<SHA>`, chọn L4 (hoặc A100) rồi **Run all**. Xem
[`yolov8s-960-v1.1-main-s0`](experiments/yolov8s-960-v1.1-main-s0/README.md).

## Chấm test và đo thời gian · v1.1

[`predict_test_v1.1.ipynb`](notebooks/predict_test_v1.1.ipynb) chấm test 5 lượt chính theo
[cấu hình đã chốt](experiments/model-selection-v1.1.md#chốt-cấu-hình-trước-khi-đo-test).
Notebook chạy gọn trên GPU **T4** miễn phí. Mỗi nhóm chạy trong một runtime riêng, vì môi trường
giống notebook train của nhóm đó và các nhóm xung đột phụ thuộc:

| `SIGNA_GROUP` | Lượt | Môi trường |
| --- | --- | --- |
| `yolov8` | `yolov8{n,s}-640-v1.1-main-s0` | `ultralytics==8.3.203` |
| `yolov5` | `yolov5{n,s}-640-v1.1-main-s0` | repo YOLOv5 gốc `1e7cf74…`, `requirements.txt` của repo và `ultralytics` ghim (xem dưới) |
| `detr` | `detr-r50-640-v1.1-main-s0` | `signa_ai.detr`, `transformers==4.57.6` như extra `detr` |

1. Mở notebook trên Colab, chọn **T4 GPU**. `signa-v1.1.zip` ở gốc My Drive.
2. Sửa ô mã đầu: `%env SIGNA_GROUP=<nhóm>`. Đặt `SIGNA_NOTEBOOK_COMMIT` theo SHA trên permalink.
3. **Run all**. Đổi nhóm thì chọn **Disconnect and delete runtime** rồi mở lại.

`requirements.txt` của YOLOv5 `1e7cf74` chỉ ghi `ultralytics>=8.4.201`, mà `AutoShape` gọi
`ultralytics.utils.ops` khi NMS và chiếu box. `meta.json` của lượt train YOLOv5 không ghi bản
`ultralytics`. Notebook đọc `My Drive/Signa/experiments/yolov5{n,s}-…/requirements.txt` do lượt
train ghi rồi ghim đúng bản đó. Thiếu tệp thì ghim `8.4.201`, cận dưới trên và cũng là bản
notebook xuất TFLite YOLOv5 dùng. Bản đã ghim và nguồn ghi trong `test-config.json`.

Notebook lấy mã tại `REPO_COMMIT` cố định. GT là `coco/val.json` và `coco/test.json` trong ZIP,
và được khóa bằng SHA-256. GT val có SHA `e3ccd276…08e2`, GT test có SHA
`38cad06f…a9950`. Notebook dừng nếu một SHA lệch. [SHA đầy đủ](experiments/test-v1.1.md#nguồn-và-tái-tạo)

Với mỗi lượt:

1. Kiểm SHA-256 `best.pt` với bảng chốt. Lệch thì dừng.
2. Xuất lại dự đoán val bằng chính hàm xuất của notebook train (conf 0,001, IoU NMS 0,7,
   `max_det` 300, ảnh 640; DETR batch 16, FP16). Chấm bằng `signa_ai.evaluate` và so với
   `experiments/<lượt>/metrics.json`. Nếu |Δ mAP@0.5:0.95| hoặc |Δ mAP@0.5| vượt 0,2 điểm
   phần trăm thì dừng, không chạy test. Số lệch ghi trong `reproduce-val.json`.
3. Xuất dự đoán test và chấm với `--manifest` và `--threshold` là ngưỡng vận hành đã chốt.
   Precision, recall và recall biển cấm trong `metrics-test.json` tính tại ngưỡng đó.
   Không chọn lại ngưỡng trên test.
4. Đo thời gian đầu cuối trên 200 ảnh val đầu tiên, khởi động 10 ảnh. Mỗi lần đo đi từ bytes
   JPEG trong bộ nhớ qua giải mã, tiền xử lý, suy luận, hậu xử lý tới box theo pixel ảnh gốc,
   ở ngưỡng vận hành. Đo trên GPU rồi CPU của runtime. Các lần chạy mới đặt lại số luồng PyTorch
   sau lần gọi khởi động đầu tiên, tối đa 4 luồng. Ghi p50, p95 và trung bình để tham khảo NFR-02.
   10 ảnh khởi động là 10 ảnh đầu của chính 200 ảnh đo. Trên GPU, DETR chạy FP16 autocast,
   YOLO chạy FP32, như đường val của từng mô hình. Khi so p95 cần nêu rõ (trường `precision`).

Kết quả ở `My Drive/Signa/experiments/<lượt>/evaluation/`:

| Tệp | Nội dung |
| --- | --- |
| `predictions-val-repro.json`, `metrics-val-repro.json`, `reproduce-val.json` | Kiểm tái tạo val |
| `predictions-test.json`, `metrics-test.json` | Dự đoán và chỉ số test |
| `test-config.json` | Cấu hình đã khóa, SHA trọng số, GT, dự đoán và chỉ số test, cổng tái tạo val, phiên bản thư viện, GPU, commit |
| `latency.json` | Định danh hồ sơ test, thời gian GPU và CPU, phần cứng và số luồng |

Bản bàn giao trong Git có các tệp dự đoán đã kiểm tra ở dạng nén để chấm lại. [Báo cáo test v1.1](experiments/test-v1.1.md#tệp-đầu-vào-chấm-lại)

Nhật ký ở `My Drive/Signa/experiments/predict-test-v1.1-<nhóm>.log`, gồm cả đầu ra của
`signa_ai.evaluate` và traceback khi lỗi. Notebook chỉ dùng lại hồ sơ test khớp cấu hình khóa,
cổng tái tạo val và SHA-256 các tệp đầu ra. Hồ sơ thời gian còn phải khớp phần cứng, số luồng
và danh sách ảnh. Notebook dừng trước khi ghi kết quả nếu hồ sơ dở dang, lệch hoặc thiếu trường
mới. Để chạy mới, lưu riêng hồ sơ cũ trước. Các kết quả đã công bố vẫn chấm lại được từ bản dự đoán lưu trong Git.
Lỗi ở ô cài đặt và ô dữ liệu chỉ hiện trong ô Colab, chưa vào nhật ký Drive. Một lượt rớt
cổng val thì cả nhóm dừng, lượt sau không chạy.
Kiểm thử CPU ở [`tests/test_predict_test_notebook.py`](tests/test_predict_test_notebook.py).
Hàm xuất được so với mọi bản notebook train tới `REPO_COMMIT` qua `git`, nên CI checkout
toàn bộ lịch sử (`fetch-depth: 0`).

Đã chấm test và đo thời gian cho cả năm lượt. YOLOv8n, YOLOv8s và DETR-R50 chạy trên
Colab T4. Hai lượt YOLOv5 chạy trên GPU RTX 3050 của laptop.
NFR-02 vẫn chờ phép đo qua backend trên server tham chiếu sau khi chốt Q7.
[Báo cáo test PyTorch](experiments/test-v1.1.md), [Hồ sơ HTTP](experiments/service-v1.1/README.md)

## Huấn luyện YOLOv8n trên Colab · v1.1

Mở [notebook](notebooks/train_yolov8n_v1.1.ipynb) bằng **Upload notebook** tại
[Google Colab](https://colab.research.google.com/), chọn **T4 GPU** hoặc **L4 GPU**.
Notebook đã chạy đủ 100 epoch trên **NVIDIA L4**; vẫn chạy được trên T4.
Hồ sơ thật từ Drive nằm tại [experiment](experiments/yolov8n-640-v1.1-temp-s0/):
epoch tốt nhất 71, mAP50–95 val Ultralytics **tham khảo** 0,70177,
phiên train dùng 1,320266 giờ GPU (không gồm smoke/xuất dự đoán).
Notebook lấy helper/danh mục từ commit `ef46f19` đã có trên main; không cần push nhánh này.

1. Tải `signa-v1.1.zip` lên thư mục gốc **My Drive**.
   Xem [cách dựng dữ liệu v1.1](data/README.md#bộ-dữ-liệu-v11).
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

## Xuất TFLite trên Colab · v1.1

1. Tải `signa-v1.1.zip` lên gốc **My Drive**. Đặt checkpoint của nhóm tại
   `MyDrive/Signa/experiments/<SOURCE_RUN>/weights/best.pt`.
2. Mở [notebook YOLOv8n](notebooks/export_tflite_v1.1.ipynb) hoặc
   [notebook YOLOv5n](notebooks/export_tflite_yolov5_v1.1.ipynb) trên Colab.
   Dùng một runtime **CPU riêng** cho mỗi mô hình. Mặc định `SOURCE_RUN` là
   `yolov8n-640-v1.1-main-s0` hoặc `yolov5n-640-v1.1-main-s0`. Để chạy
   thử YOLOv8n, đặt `SOURCE_RUN = "yolov8n-640-v1.1-temp-s0"` ở ô đầu.
3. Chọn **Run all**. Notebook tạo môi trường Python 3.11 riêng, lấy mã tại
   `REPO_COMMIT` rồi xuất 640/480 px × FP16/INT8. YOLOv8 ghim
   Ultralytics 8.3.203, TensorFlow 2.19.0, tf-keras 2.19.0, ONNX 1.17.0,
   ONNX Runtime 1.18.1, onnxslim 0.1.71 và onnx2tf 1.28.2. YOLOv5 ghim repo gốc ở
   `1e7cf74cb64d719b34f7315aa13eab9e32cde672`, Ultralytics 8.4.201 và
   TensorFlow 2.15.1. Phiên bản đầy đủ của các gói xuất nằm trong ô 2.
   Ô 3 hiển thị trực tiếp stdout và stderr, đồng thời nối nhật ký vào
   `MyDrive/Signa/exports/<SOURCE_RUN>/export.log` với giờ bắt đầu và
   kết thúc theo UTC, mã thoát và dòng cuối của thanh tiến trình.
4. Xem `MyDrive/Signa/exports/<SOURCE_RUN>/summary.csv`. Bảng tổng hợp
   mAP@0.5, mAP@0.5:0.95, recall nhóm biển cấm tại ngưỡng F1 tối đa,
   kích thước MB (1 MB = 1.000.000 byte) và trung bình ms/ảnh cho
   `Interpreter.invoke()` trên CPU Colab (tối đa 4 luồng, 5 lượt khởi động).
   Bảng và metadata ghi model CPU, số CPU logic và số luồng thực tế.
   Dùng các số CPU này để tham khảo khi so sánh trong cùng runtime.

Luồng YOLOv8 dùng ONNX opset 19. Với Torch 2.8 và ONNX 1.17, exporter
mặc định chọn opset 22. Mức này vượt giới hạn 20 của đường xuất ONNX qua
TorchScript trong Torch 2.8 và giới hạn 21 của ONNX Runtime 1.18.1.
Khi ONNX Runtime từ chối đồ thị, onnxslim có thể bỏ qua bước gấp hằng số
và onnx2tf bỏ qua suy luận kiểm tra tensor trung gian. Ultralytics từng ghi
nhận lỗi shape `32/16` tại nút residual `model.2/m.0/Add` khi đồ thị ONNX
chưa được đơn giản hóa đầy đủ.
[Giới hạn exporter PyTorch](https://github.com/pytorch/pytorch/blob/v2.8.0/torch/onnx/_constants.py),
[Bảng tương thích ONNX Runtime](https://onnxruntime.ai/docs/reference/compatibility.html#onnx-opset-support),
[phân tích upstream](https://github.com/ultralytics/ultralytics/pull/21758#issuecomment-3211548953).

YAML hiệu chỉnh chứa danh sách lấy mẫu với seed 0 từ manifest train v1.1
(300 ảnh cho YOLOv8, 101 ảnh cho YOLOv5). Khóa `train` và `val` trong YAML
**hiệu chỉnh** đều trỏ đến danh sách train vì schema exporter cần cả hai.
YOLOv8 gọi exporter với `split="train"` và dùng tệp
`*_full_integer_quant.tflite`. YOLOv5 đọc khóa `train` và xuất tensor
`uint8` cho bản INT8. [Exporter YOLOv8 đã ghim](https://github.com/ultralytics/ultralytics/blob/v8.3.203/ultralytics/engine/exporter.py),
[exporter YOLOv5 đã ghim](https://github.com/ultralytics/yolov5/blob/1e7cf74cb64d719b34f7315aa13eab9e32cde672/export.py).

Mỗi thư mục cấu hình (ví dụ `640-int8/`) có `model.tflite`,
`predictions-val.json`, `metrics.json` và `export-meta.json`. Dự đoán
dùng COCO val, confidence 0,001, NMS theo lớp với IoU 0,7 và tối đa 300
box mỗi ảnh. Metadata ghi SHA mã nguồn, SHA checkpoint, phiên bản thư
viện, kích thước và hợp đồng tensor. `requirements.txt` cạnh `summary.csv`
ghi phiên bản của mọi gói đã cài trong môi trường xuất. Khi chạy lại, notebook dùng kết quả
cũ nếu thiết lập và SHA-256 của các tệp khớp. `summary.csv` được cập nhật
sau mỗi cấu hình hoàn tất.

### Kết quả YOLOv5n trên Colab CPU

Checkpoint `yolov5n-640-v1.1-main-s0` đạt mAP@0.5 / mAP@0.5:0.95
**78,35% / 61,58%** trên val với PyTorch và bộ chấm chung. Lượt xuất dùng
mã tại `0939673` trên Colab CPU 2 vCPU (Intel Xeon 2,20 GHz), với 2 luồng
suy luận.
[CSV kết quả YOLOv5n](experiments/yolov5n-640-v1.1-main-s0/tflite-summary.csv).

| Cấu hình | Kích thước (MB) | mAP@0.5 (%) | mAP@0.5:0.95 (%) | Recall biển cấm (%) | Ngưỡng F1 tối đa | ms/ảnh |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| 640 FP16 | 3,763 | 78,34 | 61,47 | 83,44 | 0,398 | 137,40 |
| 640 INT8 | 2,061 | 58,46 | 36,63 | 63,12 | 0,831 | 164,50 |
| 480 FP16 | 3,753 | 75,82 | 58,03 | 78,57 | 0,372 | 80,75 |
| 480 INT8 | 2,054 | 53,31 | 31,15 | 51,98 | 0,848 | 96,66 |

YOLOv5 ghép `xywh` đã chuẩn hóa, objectness và xác suất lớp vào một tensor.
Exporter INT8 xuất tensor đó dưới dạng `uint8` với một cặp `scale` và
`zero_point` dùng chung. Signa đọc cặp này từ chính tensor đầu ra và giải
lượng tử như `DetectMultiBackend` mà `detect.py` gọi.
Ở đầu ra lượng tử hóa, mỗi bước tọa độ ứng với `scale × S` pixel và các điểm
gần nhau có thể rơi vào cùng một mức. Với biển chỉ khoảng 10 px sau khi
resize về đầu vào 640, sai số vài pixel có thể làm IoU với box chuẩn giảm
mạnh. Làm tròn cũng có thể đẩy IoU giữa hai box trùng xuống dưới ngưỡng
NMS 0,7, để lại dự đoán dương tính giả có điểm cao. Cơ chế này phù hợp
với việc ngưỡng F1 tối đa tăng từ 0,398 lên 0,831 ở 640. Số liệu tổng hợp
chưa tách được sai số đầu ra khỏi sai số từ toán tử INT8 bên trong.
[Đầu ra TFDetect](https://github.com/ultralytics/yolov5/blob/1e7cf74cb64d719b34f7315aa13eab9e32cde672/models/tf.py#L374-L418),
[TFLite backend YOLOv5](https://github.com/ultralytics/yolov5/blob/1e7cf74cb64d719b34f7315aa13eab9e32cde672/models/common.py#L753-L770),
[đặc tả lượng tử TensorFlow Lite](https://github.com/tensorflow/tensorflow/blob/v2.15.1/tensorflow/lite/g3doc/performance/quantization_spec.md).

Các thí nghiệm tách nguyên nhân dưới đây vẫn chưa chạy.

1. Đọc `640-int8/export-meta.json`, tính `tensors.output.scale × 640`
   theo pixel và đối chiếu scale, zero point đầu vào với `1/255` và `0`.
2. So sánh AP theo kích thước biển nhỏ, vừa và lớn giữa `640-fp16` và
   `640-int8` trong hai tệp `metrics.json`.
3. Áp thêm NMS theo lớp với IoU 0,5 lên `640-int8/predictions-val.json`,
   rồi chấm lại bằng `signa_ai.evaluate` để xem tác động của box trùng.
4. Chạy lại FP16 trên Colab, lượng tử đầu ra về `uint8` bằng scale và
   zero point của bản INT8 rồi giải mã và chấm lại cùng val. So với
   FP16 gốc để đo phần sai số do đầu ra, sau đó so với INT8 để xem
   mức chênh còn lại khi các toán tử bên trong cũng được lượng tử hóa.

Ưu tiên FP16 cho YOLOv5n khi đo trên Android. Nếu cần giảm dung lượng, thử
lượng tử hóa phần trích đặc trưng và giữ tầng dự đoán cùng phép giải mã ở
float, sau đó chấm lại cùng val. Đặt riêng `inference_output_type=tf.float32`
chỉ đổi kiểu đầu ra ở biên khi toán tử tạo dự đoán vẫn chạy INT8. Phép giải
lượng tử ở biên giữ nguyên sai số đã phát sinh trong đồ thị.
[Các chế độ lượng tử hóa TensorFlow Lite](https://developers.google.com/edge/litert/conversion/tensorflow/quantization/post_training_quantization).

### Kết quả YOLOv8n trên Colab CPU

Checkpoint `yolov8n-640-v1.1-main-s0` đạt mAP@0.5 / mAP@0.5:0.95
**81,46% / 65,57%** trên val với PyTorch và bộ chấm chung.
[Kết quả PyTorch](experiments/main-v1.1.md).

Metadata `identity.repo_commit` ghi mã xuất `9659c32` cho cả bốn cấu hình. Lượt xuất chạy trên Colab CPU 2 vCPU (Intel Xeon 2,20 GHz),
với 2 luồng suy luận. [CSV kết quả YOLOv8n](experiments/yolov8n-640-v1.1-main-s0/tflite-summary.csv).

| Cấu hình | Kích thước (MB) | mAP@0.5 (%) | mAP@0.5:0.95 (%) | Recall biển cấm (%) | Ngưỡng F1 tối đa | ms/ảnh |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| 640 FP16 | 6,201 | 81,50 | 65,55 | 80,45 | 0,429 | 223,16 |
| 640 INT8 | 3,284 | 74,62 | 52,91 | 70,63 | 0,796 | 235,45 |
| 480 FP16 | 6,157 | 78,47 | 62,49 | 75,30 | 0,398 | 130,30 |
| 480 INT8 | 3,262 | 70,40 | 50,55 | 65,69 | 0,801 | 137,65 |

Lần chạy đầu lỗi vì môi trường xuất không nhận backend Matplotlib inline
của Colab. Commit `62ac220` sửa lỗi bằng cách đặt `MPLBACKEND=Agg` cho
tiến trình xuất. Nhật ký `export-yolov8n.log` ghi lần chạy lại hoàn tất cả
bốn cấu hình và thoát với mã `0` lúc `2026-11-21T05:42:44Z`.
`62ac220` chỉ sửa notebook. Còn `9659c32` là mã xuất mà notebook ghim qua
`REPO_COMMIT`, nên cả hai lần chạy đều ghi `9659c32`. Nhật ký không ghi
commit notebook của lần chạy lại.

### So sánh ứng viên điện thoại (AI-04)

Recall biển cấm đo ở ngưỡng F1 tối đa của từng cấu hình.

| Cấu hình | YOLOv5n mAP@0.5 val (%) | YOLOv5n recall biển cấm (%) | YOLOv5n (MB) | YOLOv8n mAP@0.5 val (%) | YOLOv8n recall biển cấm (%) | YOLOv8n (MB) |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| 640 FP16 | 78,34 | **83,44** | 3,763 | **81,50** | 80,45 | 6,201 |
| 640 INT8 | 58,46 | 63,12 | 2,061 | 74,62 | 70,63 | 3,284 |
| 480 FP16 | 75,82 | 78,57 | 3,753 | 78,47 | 75,30 | 6,157 |
| 480 INT8 | 53,31 | 51,98 | 2,054 | 70,40 | 65,69 | 3,262 |

Ở ngưỡng F1 tối đa, cả tám cấu hình đều có recall biển cấm trên val dưới 0,85.
Cao nhất là YOLOv5n 640 FP16 với 83,44%, còn YOLOv8n 640 FP16 đạt 80,45%.
Trên val, chỉ YOLOv8n 640 FP16 vượt 80% mAP@0.5. `docs/04` §4 chọn ngưỡng
điện thoại thấp hơn F1 tối đa để ưu tiên recall. Ở ngưỡng điện thoại, YOLOv8n 640 FP16
đạt recall biển cấm 85,04% với precision 71,95% (ngưỡng 0,0426). YOLOv5n
640 FP16 đạt cùng recall với precision 78,76% (ngưỡng 0,241). Sáu cấu hình
còn lại chưa đo ở ngưỡng điện thoại. Ngưỡng đầy đủ và cách tính nằm trong
[đề xuất triển khai v1.1](experiments/model-selection-v1.1.md#ngưỡng-ưu-tiên-recall-biển-cấm).

Trên ảnh test, YOLOv8n TFLite 640 FP16 đạt mAP@0.5 78,91%, thiếu 1,09 điểm phần trăm so với
NFR-05. Recall biển cấm trước tracker đạt 85,80% ở ngưỡng đã khóa. Nghiệm thu NFR-06
còn chờ phép đo với tracker trên video thực địa. [Báo cáo test TFLite](experiments/yolov8n-640-v1.1-main-s0/tflite-640-fp16-evaluation/README.md)

Ở 640 px, khi chuyển từ FP16 sang INT8, mAP@0.5 val giảm 6,88 điểm phần trăm
với YOLOv8n và 19,87 điểm phần trăm với YOLOv5n. Ở 480 px, mức giảm lần lượt
là 8,07 và 22,51 điểm phần trăm. Hai luồng xuất khác số ảnh hiệu chỉnh
(300 / 101), công cụ (TF 2.19 + onnx2tf / TF 2.15) và kiểu đầu ra
(`int8` / `uint8`). Vì vậy chưa quy được chênh lệch này cho kiến trúc.

Cả tám cấu hình đều dưới 15 MB. FPS toàn chuỗi trên Android **chưa đo**.
Thành viên 3 phụ trách đo trên thiết bị tham chiếu sau khi chốt Q7.
Đề xuất tạm YOLOv8n 640 FP16 vì có mAP@0.5 val cao nhất. Chọn mAP@0.5 val cao nhất
trong số các cấu hình đạt NFR-01 (≥ 15 FPS), NFR-07 (< 15 MB) và mốc recall
NFR-06 trên ảnh test trước tracker tại ngưỡng đã khóa.
[Đề xuất triển khai v1.1](experiments/model-selection-v1.1.md#điện-thoại-fr-03),
[Quy tắc AI-04](../docs/04-thiet-ke-ai.md#chọn-mô-hình-triển-khai--ai-04),
[Đặc tả · Q7](../docs/02-dac-ta-yeu-cau.md#11-vấn-đề-cần-làm-rõ).

### Hợp đồng đầu vào, đầu ra và nhãn

- Đầu vào là RGB `[1,S,S,3]` theo NHWC. Resize bilinear theo tâm pixel,
  làm tròn kích thước, đệm 114 ở hai bên hoặc trên dưới. Đặt pixel đệm
  lẻ ở bên phải hoặc dưới. Chuẩn hóa RGB vào [0, 1]. Với tensor 8 bit,
  dùng `round(value / scale + zeroPoint)` và cắt theo miền của kiểu dữ
  liệu trong metadata. Khi đúng điểm giữa, làm tròn về số chẵn.
- Đầu ra là `[1,4+nc,N]` cho YOLOv8 và `[1,N,5+nc]` cho YOLOv5. Với
  tensor 8 bit, giải lượng tử bằng `(q - zeroPoint) × scale`. Bốn kênh
  đầu là `cx, cy, w, h` theo tỷ lệ kích thước đầu vào. Lấy điểm lớp cao
  nhất và nhân thêm objectness cho YOLOv5. Sau khi giải mã, trừ phần đệm
  và chia theo tỷ lệ resize thực tế của từng trục để chiếu box về ảnh gốc.
  Loại box không còn diện tích nhìn thấy trong ảnh gốc. Chạy NMS theo lớp
  với ngưỡng IoU 0,7 trên box **chưa cắt** trong hệ tọa độ đầu vào. Giữ tối
  đa 300 box mỗi ảnh, rồi trả các box đã cắt theo biên ảnh gốc.
  [Mã tham chiếu](src/signa_ai/mobile.py) · [Ca đối chiếu ở biên ảnh](tests/test_mobile.py).
- `labels.json` nằm cạnh `summary.csv`. JSON UTF-8 có `version` là
  phiên bản danh mục, `categories` ánh xạ khóa nhóm đến đối tượng
  `{"name": "..."}`, và `classes` sắp theo `id` từ 0 đến 45. Mỗi lớp
  có `id`, `code`, `category`, `name` và `speed_kmh` (số hoặc `null`).
  `id` là chỉ số kênh lớp và COCO `category_id`. Đóng gói tệp nhãn cùng
  mô hình đã chọn trong `assets/`.

### Đo FPS trên Android

1. Trên thiết bị tham chiếu, đo từng tệp dưới 15 MB với cùng
   bản `benchmark_model` cho Android và cùng số luồng. Đẩy binary và mô
   hình lên thiết bị qua ADB như ví dụ dưới đây. Lưu log CPU XNNPACK
   và GPU delegate riêng để đối chiếu độ trễ detector. Ghi lỗi hoặc
   delegate thực tế khi có fallback. [TFLite benchmark_model](https://github.com/tensorflow/tensorflow/tree/v2.19.0/tensorflow/lite/tools/benchmark).
2. Trong app, phát lại cùng video val và dùng cùng chế độ xử lý khung,
   letterbox, detector, NMS và tracker. Khởi động một phút rồi chia
   số khung xử lý xong cho thời gian đo bằng đồng hồ đơn điệu. Đo GPU
   delegate và CPU XNNPACK, ghi FPS toàn chuỗi, độ trễ từng bước và FPS
   sau 20 phút. Ghi tên thiết bị, bản Android, bản app/TFLite, video,
   cấu hình, delegate thực tế, số luồng, trạng thái nhiệt và log.
3. Giữ các cấu hình đạt ≥ 15 FPS toàn chuỗi (NFR-01), < 15 MB
   (NFR-07) và recall biển cấm ≥ 0,85 trên ảnh test trước tracker ở ngưỡng đã khóa (NFR-06),
   rồi chọn mAP@0.5 val lớn nhất trong số đó. Đối chiếu
   [quy tắc chọn mô hình](../docs/04-thiet-ke-ai.md#4-giao-thức-đánh-giá-và-chọn-mô-hình)
   và [giao thức đo thiết bị](../docs/08-kiem-thu.md#4-đánh-giá-trên-thiết-bị).

```sh
adb push benchmark_model model.tflite /data/local/tmp/
adb shell chmod +x /data/local/tmp/benchmark_model
adb shell /data/local/tmp/benchmark_model --graph=/data/local/tmp/model.tflite \
  --num_threads=4 --warmup_runs=20 --num_runs=100 --use_xnnpack=true --use_gpu=false
adb shell /data/local/tmp/benchmark_model --graph=/data/local/tmp/model.tflite \
  --num_threads=4 --warmup_runs=20 --num_runs=100 --use_xnnpack=false --use_gpu=true
```
