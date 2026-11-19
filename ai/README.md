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

## Huấn luyện YOLOv8n trên Colab · v1.1

Mở [notebook](notebooks/train_yolov8n_v1.1.ipynb) bằng **Upload notebook** tại
[Google Colab](https://colab.research.google.com/), chọn **T4 GPU** hoặc **L4 GPU**.
Notebook đã chạy đủ 100 epoch trên **NVIDIA L4**; vẫn chạy được trên T4.
Hồ sơ thật từ Drive nằm tại [experiment](experiments/yolov8n-640-v1.1-temp-s0/):
epoch tốt nhất 71, mAP50–95 val Ultralytics **tham khảo** 0,70177,
phiên train dùng 1,320266 giờ GPU (không gồm smoke/xuất dự đoán).
Chưa có kết quả từ bộ đánh giá chung `evaluate.py`, nên chưa tạo `metrics.json`.
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
   còn thiếu bộ tăng cường thời tiết/nén ảnh chung trong `configs/augment.md`.
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
