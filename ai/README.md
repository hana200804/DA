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
| `experiments/` | Cấu hình, log, chỉ số của từng thí nghiệm | Huấn luyện YOLO, DETR |
| `service/` | Dịch vụ nhận diện FastAPI | Dịch vụ nhận diện |

Ảnh, video và trọng số không đưa vào Git.

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
