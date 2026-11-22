# yolov8s-960-v1.1-main-s0

Thí nghiệm AI-07 về biển nhỏ ([Thiết kế AI §3](../../../docs/04-thiet-ke-ai.md#3-giao-thức-huấn-luyện), rủi ro "Biển nhỏ, ở xa" ở §9).

**Trạng thái: chưa chạy.** Chưa có số đo nào cho lượt này.

## Mục tiêu

Đo tác động của ảnh đầu vào lớn hơn lên biển nhỏ. So với lượt `yolov8s-640-v1.1-main-s0`, chỉ đổi kích thước ảnh từ 640 lên 960.

## Khác biệt so với lượt 640

| Hạng mục | `yolov8s-640-v1.1-main-s0` | Lượt này |
| --- | --- | --- |
| Kích thước ảnh train và xuất dự đoán val | 640 | 960 |
| Batch | 16 | 16. Nếu hết bộ nhớ GPU phải giảm, `config.yaml` ghi `batch_note` và báo cáo khác biệt này |

Giữ nguyên: dữ liệu v1.1, [tăng cường chung](../../configs/augment.md), khởi tạo COCO `yolov8s.pt`, seed 0, trần 200 epoch, patience 30 theo mAP@0.5:0.95 trên val, cách chọn `best.pt`, ngưỡng xuất dự đoán 0,001.

## Cách chạy

Mở [`train_yolov8_main_v1.1.ipynb`](../../notebooks/train_yolov8_main_v1.1.ipynb) trên Colab và chọn GPU L4 (hoặc A100). Thêm một ô mã ở đầu notebook, không sửa ô mã nào khác:

```text
%env SIGNA_NOTEBOOK_COMMIT=<SHA>
%env SIGNA_IMGSZ=960
%env SIGNA_SIZES=s
```

Thay `<SHA>` bằng SHA trên liên kết cố định GitHub của notebook, rồi **Run all**. Mất phiên thì giữ ô này và **Run all** lại, run dở tiếp tục từ `last.pt`. Nếu hết bộ nhớ GPU, thêm `%env SIGNA_BATCH=<số nhỏ hơn>`; `config.yaml` sẽ ghi `batch_note`.

Ước lượng thời gian, chưa đo: lượt 640 mất 6,1 giờ L4 ([main-v1.1.md](../main-v1.1.md)). Ảnh 960 có khoảng 2,25 lần số điểm ảnh. Nếu thời gian tăng theo số điểm ảnh, lượt này cần khoảng 13,7 giờ L4. Khi tiếp tục từ checkpoint, bộ đếm dừng sớm khởi tạo lại. Nếu lượt chạy qua nhiều phiên, ghi lại khác biệt này.

Sau khi chạy, chép `config.yaml`, `meta.json`, `results.csv`, `best-selection.json`, `metrics.json` từ Drive `Signa/experiments/yolov8s-960-v1.1-main-s0/` vào thư mục này.

## Tiêu chí so sánh

So với `yolov8s-640-v1.1-main-s0` bằng `metrics.json` của bộ đánh giá chung `signa_ai.evaluate` trên val v1.1:

| Chỉ số | Lượt 640 | Lượt này |
| --- | --- | --- |
| AP box nhỏ (< 32² px) | 51,1% | chưa đo |
| mAP@0.5 | 84,03% | chưa đo |
| mAP@0.5:0.95 | 69,56% | chưa đo |
| Recall biển cấm (ở ngưỡng F1 tối đa) | 79,75% | chưa đo |
| GPU · giờ | L4 · 6,1 | chưa đo |

Số của lượt 640 lấy từ [main-v1.1.md](../main-v1.1.md). Với một seed, khác biệt nhỏ chưa đủ để kết luận.
