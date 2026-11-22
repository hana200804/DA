# Thí nghiệm

Mỗi lượt huấn luyện có một thư mục `ai/experiments/<mã thí nghiệm>/` ([Thiết kế AI §3](../../docs/04-thiet-ke-ai.md#3-giao-thức-huấn-luyện)).

Đặt mã theo dạng `<mô hình>-<cỡ>-<ảnh>-<ghi chú>-s<seed>`, ví dụ `yolov8n-640-base-s0` hoặc `rtdetr-l-640-synth-s1`.

| File | Nội dung | Có trong Git |
| --- | --- | --- |
| `config.yaml` | Toàn bộ siêu tham số, phiên bản dữ liệu, phiên bản danh mục nhãn | Có |
| `meta.json` | Commit, thời điểm, GPU, số giờ GPU, epoch dừng | Có |
| `metrics.json` | Kết quả của bộ đánh giá chung trên val | Có |
| `error-analysis-val.json` | Phân tích lỗi tại ngưỡng F1 tối đa và SHA-256 của đầu vào trên val | Có |
| `log.txt` hoặc `results.csv` | Log huấn luyện | Có nếu nhỏ hơn 5 MB |
| `weights/best.pt` | Trọng số tốt nhất | Không. Lưu trên ổ chung hoặc MinIO, ghi đường dẫn trong `meta.json` |
| `model_card.json` | Thẻ mô hình (AI-06) cho ứng viên và mô hình phát hành | Có |

Không chạy bộ đánh giá trên test hoặc tập thực địa trước khi chốt cấu hình ([Thiết kế AI §4](../../docs/04-thiet-ke-ai.md#chọn-mô-hình-triển-khai--ai-04)).

Các báo cáo v1.1 gồm [kết quả lượt chính](main-v1.1.md), [phân tích lỗi trên val](error-analysis-v1.1-val.md) và [đề xuất triển khai](model-selection-v1.1.md).

## Sinh thẻ mô hình

Chạy trong `ai/` với `config.yaml`, `meta.json`, `best-selection.json` và `metrics.json` của lượt. Số liệu val lấy từ bộ đánh giá chung. Phiên bản mô hình ứng viên đầu tiên là `v1.0`. Phiên bản dữ liệu nằm trong `datasetVersion`. Trường chưa có số đo như kích thước checkpoint PyTorch, độ trễ server, FPS thiết bị, kết quả test và thực địa được ghi `null`.

```sh
cache="$HOME/signa-cache"
run=yolov8n-640-v1.1-main-s0
.venv/bin/python scripts/analyze_errors.py \
  --gt "$cache/val-v1.1/gt/ground-truth-val.json" \
  --pred "$cache/tflite-640-fp16/yolov8n/predictions-val.json" \
  --target-prohibitory-recall 0.85 --out /tmp/signa-yolov8n-tflite-val.json
.venv/bin/python scripts/make_model_card.py "experiments/$run" --version v1.0 \
  --export-config "$cache/val-v1.1/$run/export-config.json" \
  --tflite "$cache/tflite-summary-yolov8n.csv" \
  "$cache/tflite-640-fp16/yolov8n/export-meta.json" /tmp/signa-yolov8n-tflite-val.json
```

Bỏ `--tflite` với thẻ chỉ có checkpoint server. Nếu hồ sơ DETR-R50 chưa có ở nhánh hiện tại, lấy bốn tệp nguồn bằng `git show <commit>:ai/experiments/detr-r50-640-v1.1-main-s0/<tệp>`. Dùng thư mục đầu vào có cùng tên lượt và `--out` để ghi thẻ vào `experiments/`. Thẻ DETR-R50 hiện tại dùng [hồ sơ tại commit c49c2d9](https://github.com/hana200804/DA/tree/c49c2d99ab13b378c00887e13631ed40996de400/ai/experiments/detr-r50-640-v1.1-main-s0).
