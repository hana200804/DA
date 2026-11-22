# yolov8s-960-v1.1-main-s0

Thí nghiệm AI-07 về biển nhỏ ([Thiết kế AI §3](../../../docs/04-thiet-ke-ai.md#3-giao-thức-huấn-luyện), rủi ro "Biển nhỏ, ở xa" ở §9).

**Trạng thái:** đã kiểm tra suy luận 640/960 trên val và chạy thử huấn luyện với 2% tập train. Lượt huấn luyện chính 960 chưa hoàn thành.

## Mục tiêu

Đo tác động của ảnh đầu vào lớn hơn lên biển nhỏ. So với lượt `yolov8s-640-v1.1-main-s0`, chỉ đổi kích thước ảnh từ 640 lên 960.

## Kết quả suy luận trên val với cùng checkpoint 640

Hai phép đo dùng toàn bộ 1.599 ảnh val v1.1 và cùng `best.pt` của YOLOv8s 640, với confidence xuất COCO 0,001, NMS IoU 0,7 và ngưỡng vận hành khóa 0,5279324054718018. Chỉ thay kích thước đầu vào khi suy luận. [Cấu hình và hash](inference-val/comparison-config.json)

`base_commit` là commit nền của PR #22. `script_sha256` khớp từng byte với bản `compare_val_sizes.executed.py` lưu dưới đây. Bản tại head ban đầu `8b39640` có cách tìm tệp nhãn và định dạng mã khác. [Bản đã chạy](inference-val/compare_val_sizes.executed.py), [Script tại head ban đầu](https://github.com/hana200804/DA/blob/8b39640d8e37ae005fec03747de39ecc1e0424eb/ai/scripts/compare_yolov8s_val_sizes.py)

| Chỉ số | 640 px | 960 px | Thay đổi |
| --- | ---: | ---: | ---: |
| AP box nhỏ | 51,14% | 53,75% | +2,61 điểm phần trăm |
| mAP@0.5 | 84,03% | 81,68% | −2,35 điểm phần trăm |
| mAP@0.5:0.95 | 69,54% | 67,01% | −2,53 điểm phần trăm |
| Recall biển cấm | 79,68% | 80,65% | +0,97 điểm phần trăm |
| Thời gian vòng suy luận và gom dự đoán trên val (RTX 3050) | 26,08 s | 46,64 s | +20,56 s |

Mỗi cỡ được đo một lần trên cùng đối tượng YOLO theo thứ tự 640 rồi 960. Lượt 640 tính cả phần khởi tạo ở lần gọi `predict` đầu tiên. Bộ đếm dừng trước khi ghi JSON.

Ảnh 960 cải thiện AP box nhỏ khi dùng lại checkpoint 640, đồng thời giảm cả hai chỉ số AP tổng thể. Giữ cấu hình suy luận 640 của ứng viên server. Ảnh đầu vào 960 vẫn cần thí nghiệm huấn luyện riêng để đo tác động của kích thước ảnh train. Cả hai phép đo này dùng val, còn test giữ cấu hình đã chốt. [Chỉ số 640](inference-val/metrics-val-640.json), [Chỉ số 960](inference-val/metrics-val-960.json)

Từ thư mục này, giải nén hai tệp dự đoán để chấm lại bằng `signa_ai.evaluate` với GT val v1.1. [Dự đoán 640](inference-val/predictions-val-640.json.gz), [Dự đoán 960](inference-val/predictions-val-960.json.gz)

```sh
gunzip -k inference-val/predictions-val-640.json.gz inference-val/predictions-val-960.json.gz
```

Chạy các lệnh sau từ `ai/` với extra `train,eval`, PyTorch CUDA và đúng `best.pt` của lượt chính 640. Lệnh này dùng [script hiện hành](../../scripts/compare_yolov8s_val_sizes.py).

```sh
uv run --no-sync python scripts/compare_yolov8s_val_sizes.py \
  --dataset data/processed/v1.1 \
  --weights /đường/dẫn/yolov8s-640-v1.1-main-s0/weights/best.pt \
  --out /tmp/signa-yolov8s-val-sizes
```

Lệnh sau chạy đúng bản mã đã lưu theo SHA-256. Đặt bản sao trong thư mục tạm có liên kết tới `data/` để giữ cách script tìm danh mục nhãn.

```sh
replay_dir=$(mktemp -d)
mkdir "$replay_dir/scripts"
ln -s "$PWD/data" "$replay_dir/data"
cp experiments/yolov8s-960-v1.1-main-s0/inference-val/compare_val_sizes.executed.py \
  "$replay_dir/scripts/compare_val_sizes.py"
sha256sum "$replay_dir/scripts/compare_val_sizes.py"
uv run --no-sync python "$replay_dir/scripts/compare_val_sizes.py" \
  --dataset data/processed/v1.1 \
  --weights /đường/dẫn/yolov8s-640-v1.1-main-s0/weights/best.pt \
  --out "$replay_dir/results"
```

## Khả năng huấn luyện 960

Thư mục Drive `Signa/experiments/yolov8s-960-v1.1-main-s0/` đã có cấu hình của phiên T4 ngày 22/11/2026, batch 16. `meta.json` ghi 0 giờ GPU. Khi kiểm tra, thư mục `weights/` trống và thiếu `results.csv` cùng tệp chấm val, nên chưa có checkpoint để tiếp tục phiên đó.

Thử trên RTX 3050 laptop 4 GB với cùng khởi tạo COCO, ảnh 960 và tăng cường chung. Batch 16 hết VRAM trong lượt đầu. Batch 2 chạy xong một epoch trên 134 ảnh train và chạy val. Vòng cập nhật 67 batch mất khoảng 8,2 s, mức cao nhất bộ nhớ do PyTorch giữ là 1.580 MiB tính đến khi quá trình huấn luyện trả về. Dựa vào 8,2 s cho 2% train và khoảng 1 phút chấm val trong epoch thử, dự tính khoảng 8 phút mỗi epoch và **khoảng 26 giờ** nếu chạm trần 200 epoch ở batch 2. Đây là ngoại suy theo tốc độ smoke. Những con số smoke chỉ kiểm tra tài nguyên, dữ liệu train 2% không dùng để so độ chính xác. [Chi tiết phép thử tài nguyên](train-probe.json)

Các log đã bỏ đường dẫn máy riêng. `original_log_sha256` ghi hash trước khi làm sạch. Tệp `results.csv` ghi thời gian epoch của batch 2. [Log batch 16](train-probe/train960-b16.log.gz), [Log batch 2](train-probe/train960-b2.log.gz), [CSV batch 2](train-probe/results.csv.gz)

```sh
gunzip -k train-probe/train960-b16.log.gz train-probe/train960-b2.log.gz train-probe/results.csv.gz
```

Cần phiên GPU chạy đủ lâu để hoàn tất hoặc tiếp tục từ checkpoint trước khi kết luận AI-07. Bản Colab và quy tắc khôi phục ở dưới giữ nguyên giao thức. Khi cần giảm batch, ghi cả khác biệt batch và phiên bản runtime vào hồ sơ để đọc kết quả đúng phạm vi.

## Khác biệt so với lượt 640

| Hạng mục | `yolov8s-640-v1.1-main-s0` | Lượt này |
| --- | --- | --- |
| Kích thước ảnh train và xuất dự đoán val | 640 | 960 |
| Batch | 16 | Mặc định 16. Khi chọn batch khác, `config.yaml` ghi `batch_note` và `meta.json` ghi batch theo phiên |

Giữ nguyên: dữ liệu v1.1, [tăng cường chung](../../configs/augment.md), khởi tạo COCO `yolov8s.pt`, seed 0, trần 200 epoch, patience 30 theo mAP@0.5:0.95 trên val, cách chọn `best.pt`, ngưỡng xuất dự đoán 0,001.

## Cách chạy

Mở [`train_yolov8_main_v1.1.ipynb`](../../notebooks/train_yolov8_main_v1.1.ipynb) trên Colab và chọn GPU L4 (hoặc A100). Thêm một ô mã ở đầu notebook, không sửa ô mã nào khác:

```text
%env SIGNA_NOTEBOOK_COMMIT=<SHA>
%env SIGNA_IMGSZ=960
%env SIGNA_SIZES=s
```

Thay `<SHA>` bằng SHA trên liên kết cố định GitHub của notebook, rồi **Run all**. Mất phiên thì giữ ô này và **Run all** lại. Notebook đối chiếu với cấu hình checkpoint và khôi phục patience từ `early-stopping.json` cùng epoch. Giữ tệp này cạnh `config.yaml`.

Nếu hết bộ nhớ GPU, đặt `%env SIGNA_BATCH=<số nhỏ hơn>`. Khi đổi batch sau khi đã có checkpoint, thêm `%env SIGNA_ALLOW_BATCH_CHANGE=1` cho phiên đổi batch. `meta.json` ghi batch trong checkpoint, batch của phiên vừa mở và tệp phiên bản phụ thuộc tương ứng. Giữ giá trị `SIGNA_BATCH` cho các phiên sau và báo cáo các mốc đổi batch khi so sánh với lượt 640.

Ước lượng thời gian, chưa đo: lượt 640 mất 6,1 giờ L4. Ảnh 960 có khoảng 2,25 lần số điểm ảnh. Nếu thời gian tăng theo số điểm ảnh, lượt này cần khoảng 13,7 giờ L4. [Lượt 640](../main-v1.1.md)

Sau khi chạy, chép `config.yaml`, `meta.json`, `results.csv`, `best-selection.json`, `metrics.json` từ Drive `Signa/experiments/yolov8s-960-v1.1-main-s0/` vào thư mục này.

## Tiêu chí so sánh khi hoàn tất lượt huấn luyện 960

So với `yolov8s-640-v1.1-main-s0` bằng `metrics.json` của bộ đánh giá chung `signa_ai.evaluate` trên val v1.1:

| Chỉ số | Lượt 640 | Lượt này |
| --- | --- | --- |
| AP box nhỏ (< 32² px) | 51,1% | chưa đo |
| mAP@0.5 | 84,03% | chưa đo |
| mAP@0.5:0.95 | 69,56% | chưa đo |
| Recall biển cấm (ở ngưỡng F1 tối đa) | 79,75% | chưa đo |
| GPU · giờ | L4 · 6,1 | chưa đo |

Số của lượt 640 lấy từ [main-v1.1.md](../main-v1.1.md). Với một seed, khác biệt nhỏ chưa đủ để kết luận.
