# Thí nghiệm

Mỗi lượt huấn luyện có một thư mục `ai/experiments/<mã thí nghiệm>/` ([Thiết kế AI §3](../../docs/04-thiet-ke-ai.md#3-giao-thức-huấn-luyện)).

Đặt mã theo dạng `<mô hình>-<cỡ>-<ảnh>-<ghi chú>-s<seed>`, ví dụ `yolov8n-640-base-s0` hoặc `rtdetr-l-640-synth-s1`.

| File | Nội dung | Có trong Git |
| --- | --- | --- |
| `config.yaml` | Toàn bộ siêu tham số, phiên bản dữ liệu, phiên bản danh mục nhãn | Có |
| `meta.json` | Commit, thời điểm, GPU, số giờ GPU, epoch dừng | Có |
| `metrics.json` | Kết quả của bộ đánh giá chung trên val | Có |
| `evaluation/{metrics-test,test-config,reproduce-val,latency,provenance}.json` | Chỉ số test, cấu hình, đối chiếu val, phép đo thời gian và hash đối chiếu | Có |
| [`evaluation/predictions-*.json.gz`](test-v1.1.md#tệp-đầu-vào-chấm-lại) | Dự đoán COCO dùng để chấm lại | Có khi có tệp gốc |
| `<tên gốc>.gz` cạnh hồ sơ phép thử | Nhật ký đo, mẫu thời gian từng yêu cầu và `results.csv` của phép thử | Có khi có tệp gốc |
| `error-analysis-val.json` | Phân tích lỗi tại ngưỡng F1 tối đa và SHA-256 của đầu vào trên val | Có |
| `log.txt` hoặc `results.csv` | Log huấn luyện | Có nếu nhỏ hơn 5 MB |
| `tflite-summary.csv` | Kết quả val, kích thước và thời gian CPU Colab theo cấu hình TFLite của ứng viên điện thoại | Có |
| `weights/best.pt` | Trọng số tốt nhất | Không. Lưu trên ổ chung hoặc MinIO, ghi đường dẫn trong `meta.json` |
| `model_card.json` | Thẻ mô hình (AI-06) cho ứng viên và mô hình phát hành | Có |

Lưu tệp đầu vào cạnh hồ sơ phép thử để nhóm chấm lại mà không cần chạy suy luận. Nén bằng `gzip -n -9` để bỏ tên gốc và dấu thời gian khỏi đầu tệp nén. Bỏ đường dẫn máy riêng và tên người dùng khỏi log trước khi nén. Mỗi bản nén nên dưới khoảng 2 MB.

Ghi SHA-256 của bản gốc và bản nén vào cấu hình hoặc `provenance.json`. Đối chiếu hai hash khi lấy tệp và dùng `gunzip -k <tên gốc>.gz` để giải nén. [Ví dụ chấm lại từ bản nén trong Git](test-v1.1.md#tệp-đầu-vào-chấm-lại)

Không chạy bộ đánh giá trên test hoặc tập thực địa trước khi chốt cấu hình ([Thiết kế AI §4](../../docs/04-thiet-ke-ai.md#chọn-mô-hình-triển-khai--ai-04)).

Các báo cáo v1.1 gồm [kết quả lượt chính](main-v1.1.md), [phân tích lỗi trên val](error-analysis-v1.1-val.md), [kết quả test PyTorch](test-v1.1.md) và [đề xuất triển khai](model-selection-v1.1.md).

## Sinh thẻ mô hình

Chạy trong `ai/` với `config.yaml`, `meta.json`, `best-selection.json` và `metrics.json` của lượt. Số liệu val lấy từ bộ đánh giá chung. Phiên bản mô hình ứng viên đầu tiên là `v1.0`. Phiên bản dữ liệu nằm trong `datasetVersion`. Trường chưa có số đo như kích thước checkpoint PyTorch, độ trễ server, FPS thiết bị, kết quả test và thực địa được ghi `null`.

Nguồn nằm trên Drive chung của nhóm. Đối chiếu SHA-256 trong thẻ và `export-meta.json` trước khi so kết quả.

- `My Drive/Signa/experiments/<lượt>/evaluation/export-config.json`: cấu hình xuất dự đoán val PyTorch, có SHA-256 trọng số.
- `My Drive/Signa/exports/<lượt>/<cấu hình>/predictions-val.json` và `export-meta.json`: dự đoán val và metadata của bản TFLite, ví dụ cấu hình `640-fp16`.
- `experiments/<lượt>/tflite-summary.csv` trong repo: bản sao của `My Drive/Signa/exports/<lượt>/summary.csv`.
- GT val: `data/processed/v1.1/coco/val.json` sau khi giải nén `signa-v1.1.zip` ([Bộ dữ liệu v1.1](../data/README.md#bộ-dữ-liệu-v11)), SHA-256 `e3ccd276014eadd148030941675efeaf06db1c1ad8deddcd80747d40ec8b08e2`.

```sh
drive="/path/to/My Drive/Signa"
run=yolov8n-640-v1.1-main-s0
tflite="$drive/exports/$run/640-fp16"
.venv/bin/python scripts/analyze_errors.py \
  --gt data/processed/v1.1/coco/val.json \
  --pred "$tflite/predictions-val.json" \
  --target-prohibitory-recall 0.85 --out /tmp/signa-yolov8n-tflite-val.json
.venv/bin/python scripts/make_model_card.py "experiments/$run" --version v1.0 \
  --export-config "$drive/experiments/$run/evaluation/export-config.json" \
  --tflite "experiments/$run/tflite-summary.csv" \
  "$tflite/export-meta.json" /tmp/signa-yolov8n-tflite-val.json
```

Bỏ `--tflite` với thẻ chỉ có checkpoint server. Nếu lượt đã có `evaluation/metrics-test.json` và `evaluation/test-config.json`, trình sinh tự đưa kết quả test vào trường `test`. Với bản TFLite, trình sinh đọc `tflite-<cỡ>-<độ chính xác>-evaluation/`. Trình sinh từ chối kết quả test nếu SHA-256 trọng số hoặc file TFLite, danh mục nhãn hay ngưỡng vận hành khác giá trị đã chốt trên val. Vì vậy sinh lại thẻ không làm mất kết quả test. Thẻ DETR-R50 dùng hồ sơ `detr-r50-640-v1.1-main-s0` đã có trên `main`.
