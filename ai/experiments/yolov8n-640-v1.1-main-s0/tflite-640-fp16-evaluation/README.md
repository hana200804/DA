# Đánh giá YOLOv8n TFLite 640 FP16 v1.1

| Tập | Ảnh / box | mAP@0.5 | mAP@0.5:0.95 | Precision | Recall | Recall biển cấm |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| Val | 1.599 / 2.685 | 81,50% | 65,55% | 71,95% | 84,47% | 85,04% |
| Test | 1.700 / 2.664 | 78,91% | 63,88% | 72,93% | 85,17% | 85,80% |

Cả hai lượt dùng chính file TFLite 6.201.024 byte đã xuất, với ngưỡng COCO AP 0,001 và ngưỡng vận hành 0,04258768633008003 theo val. Hai giá trị AP val tái tạo khớp điểm đã lưu trong [bảng xuất TFLite](../tflite-summary.csv). Precision và recall trong bảng tính ở ngưỡng vận hành, trước tracker. [Cấu hình trước khi đo](../../model-selection-v1.1.md#chốt-cấu-hình-trước-khi-đo-test)

| Yêu cầu | Kết quả |
| --- | --- |
| NFR-05 · mAP@0.5 trên test ≥ 80% | Thiếu 1,09 điểm phần trăm. |
| NFR-06 · recall biển cấm ≥ 85% | Đạt 85,80% trên ảnh test ở ngưỡng đã chốt. Cần đánh giá riêng chuỗi có tracker. |
| NFR-07 · file mô hình < 15 MB | File dự kiến đóng gói là 6,20 MB. Kiểm lại kích thước khi tích hợp vào `assets/`. |
| NFR-01 · tốc độ ≥ 15 FPS, cùng kiểm tra nhiệt và bộ nhớ | Chờ Android tham chiếu và chuỗi phát lại video. |

Giữ YOLOv8n 640 FP16 là ứng viên theo mAP trên val. Cần đo FPS trên video val cho các ứng viên và kiểm tra lại recall biển cấm ở ngưỡng chọn trên val trước khi chốt. Hiện chưa có Android tầm trung và thiết bị đối chiếu được nối với máy, chưa có bản ứng dụng đo camera/tracker trong repo này. Lượt thực địa còn cần video, GPS đồng bộ và nhãn chuẩn theo tuyến. [Giao thức kiểm thử trên thiết bị](../../../../docs/08-kiem-thu.md#4-đánh-giá-trên-thiết-bị)

## Thời gian tham khảo trên máy phát triển

TensorFlow Lite 2.19.0 với XNNPACK, 2 luồng trên Intel Core i5-11400H. Trung bình `invoke()` là 74,22 ms/ảnh val và 72,59 ms/ảnh test sau khi khởi động 5 lần trên ảnh đầu mỗi tập. Tổng thời gian vòng lặp xuất dự đoán, gồm đọc ảnh, letterbox, detector và giải mã đầu ra, là 218,5 s trên val và 209,9 s trên test. Lượt val chạy cùng lúc với phép chấm YOLOv5. Các số này đo trên laptop và chỉ phục vụ tham khảo runtime. Đo FPS, nhiệt và bộ nhớ phải dùng ứng dụng trên thiết bị Q7.

## Nguồn và tái tạo

File gốc ở `My Drive/Signa/exports/yolov8n-640-v1.1-main-s0/640-fp16/model.tflite`. Hash file và trọng số nguồn khớp [metadata từ Drive](export-meta.json). [Cấu hình đánh giá](test-config.json) chứa hash GT, manifest, dự đoán, các gói runtime và thời gian. [Chỉ số test](metrics-test.json) gồm AP theo kích thước, theo lớp và ma trận nhầm lẫn. Dự đoán COCO lưu trong `.local-eval/mobile/v8/` ở workspace chạy.

Chạy từ `ai/` với Python 3.11 và extra `data,eval`. Dùng `tensorflow-cpu==2.19.0` và `opencv-python-headless==5.1.0.95` như trong cấu hình bên cạnh. Thay đường dẫn `model` và `dataset` bằng bản cục bộ. Hàm dùng chung `predict_val` nhận danh sách ảnh COCO của tập cần chấm.

```python
import hashlib
import json
from pathlib import Path

from scripts.export_tflite import predict_val
from signa_ai.labels import load_label_map

model = Path("/đường/dẫn/model.tflite")
dataset = Path("data/processed/v1.1")
assert hashlib.sha256(model.read_bytes()).hexdigest() == (
    "0af0fcaef4eefd4899c2ec85bd833bec8aa3f586b04f18acb63728dc074df159"
)
labels = load_label_map(Path("data/labels.yaml"))
for split in ("val", "test"):
    truth = json.loads((dataset / f"coco/{split}.json").read_text())
    records, _, _ = predict_val(
        model, dataset, truth["images"], labels, "yolov8", 640, False, num_threads=2
    )
    Path(f"/tmp/signa-tflite-{split}.json").write_text(json.dumps(records))
```

```sh
dataset=data/processed/v1.1
for split in val test; do
  uv run --no-sync python -m signa_ai.evaluate \
    --gt "$dataset/coco/$split.json" --manifest "$dataset/manifest.csv" \
    --pred "/tmp/signa-tflite-$split.json" --threshold 0.04258768633008003 \
    --out "/tmp/signa-tflite-metrics-$split.json"
done
```
