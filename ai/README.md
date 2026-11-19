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
