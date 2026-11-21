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
| `src/signa_ai/mobile.py` | Letterbox, giải mã TFLite, NMS và sinh `labels.json` | Mô hình điện thoại |
| `notebooks/export_tflite*.ipynb`, `scripts/export_tflite.py` | Xuất và chấm các ứng viên TFLite trên Colab | Mô hình điện thoại |
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
**78,35% / 61,58%** trên val với PyTorch và bộ chấm chung. Lượt xuất ở
`eb182d9` trên Colab CPU 2 vCPU cho các kết quả sau.

| Cấu hình | mAP@0.5 (%) | mAP@0.5:0.95 (%) | Ngưỡng F1 tối đa | ms/ảnh |
| --- | ---: | ---: | ---: | ---: |
| 640 FP16 | 78,34 | 61,47 | 0,398 | 137,40 |
| 640 INT8 | 58,46 | 36,63 | 0,831 | 164,50 |
| 480 FP16 | 75,82 | 58,03 | 0,372 | 80,75 |
| 480 INT8 | 53,31 | 31,15 | 0,848 | 96,66 |

YOLOv5 ghép `xywh` đã chuẩn hóa, objectness và xác suất lớp vào một tensor.
Exporter INT8 xuất tensor đó dưới dạng `uint8` với một cặp `scale` và
`zero_point` dùng chung. Signa đọc cặp này từ chính tensor đầu ra và giải
lượng tử như `DetectMultiBackend` mà `detect.py` gọi.
Ở đầu ra lượng tử hóa, mỗi bước tọa độ ứng với `scale × S` pixel và các điểm
gần nhau có thể rơi vào cùng một mức. Số liệu tổng hợp chưa cho biết phần
giảm mAP đến riêng từ đầu ra hay từ các toán tử đã lượng tử hóa bên trong.
[Đầu ra TFDetect](https://github.com/ultralytics/yolov5/blob/1e7cf74cb64d719b34f7315aa13eab9e32cde672/models/tf.py#L374-L418),
[TFLite backend YOLOv5](https://github.com/ultralytics/yolov5/blob/1e7cf74cb64d719b34f7315aa13eab9e32cde672/models/common.py#L753-L770),
[đặc tả lượng tử TensorFlow Lite](https://github.com/tensorflow/tensorflow/blob/v2.15.1/tensorflow/lite/g3doc/performance/quantization_spec.md).

Ưu tiên FP16 cho YOLOv5n khi đo trên Android. Nếu cần giảm dung lượng, thử
lượng tử hóa phần trích đặc trưng và giữ tầng dự đoán cùng phép giải mã ở
float, sau đó chấm lại cùng val. Đặt riêng `inference_output_type=tf.float32`
chỉ đổi kiểu đầu ra ở biên khi toán tử tạo dự đoán vẫn chạy INT8. Phép giải
lượng tử ở biên giữ nguyên sai số đã phát sinh trong đồ thị.
[Các chế độ lượng tử hóa TensorFlow Lite](https://developers.google.com/edge/litert/conversion/tensorflow/quantization/post_training_quantization).

### Hợp đồng đầu vào, đầu ra và nhãn

- Đầu vào là RGB `[1,S,S,3]` theo NHWC. Resize bilinear theo tâm pixel,
  làm tròn kích thước, đệm 114 ở hai bên hoặc trên dưới. Đặt pixel đệm
  lẻ ở bên phải hoặc dưới. Chuẩn hóa RGB vào [0, 1]. Với tensor 8 bit,
  dùng `round(value / scale + zeroPoint)` và cắt theo miền của kiểu dữ
  liệu trong metadata. Khi đúng điểm giữa, làm tròn về số chẵn.
- Đầu ra là `[1,4+nc,N]` cho YOLOv8 và `[1,N,5+nc]` cho YOLOv5. Với
  tensor 8 bit, giải lượng tử bằng `(q - zeroPoint) × scale`. Bốn kênh
  đầu là `cx, cy, w, h` theo tỷ lệ kích thước đầu vào. Lấy điểm lớp cao
  nhất và nhân thêm objectness cho YOLOv5. Trừ phần đệm, chia theo tỷ
  lệ resize thực tế của từng trục rồi cắt box theo biên ảnh gốc. Xem
  mã tham chiếu tại [mobile.py](src/signa_ai/mobile.py).
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
3. Giữ các cấu hình đạt ≥ 15 FPS toàn chuỗi (NFR-01) và < 15 MB
   (NFR-07), rồi chọn mAP@0.5 val lớn nhất trong số đó. Đối chiếu
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
