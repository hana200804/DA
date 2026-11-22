# Bảng đối chiếu tăng cường dữ liệu

Danh sách phép biến đổi lấy từ [Dữ liệu §6](../../docs/05-du-lieu.md#6-tăng-cường-dữ-liệu). Ba mô hình dùng **cùng giá trị** trong lượt so sánh chính. Mỗi bộ công cụ đặt tên tham số khác nhau, nên bảng này ghi tên tham số tương ứng.

Giá trị nằm trong [`signa_ai/augment.py`](../src/signa_ai/augment.py) và [`yolov5-hyp.yaml`](yolov5-hyp.yaml). Khi đổi một giá trị, sửa bảng này, cả hai tệp và cả ba cột trong cùng pull request. `tests/test_augment.py` kiểm tra `yolov5-hyp.yaml` khớp `ULTRALYTICS_ARGS`.

## Cách áp dụng

- **Phép hình học** (co giãn, dịch, xoay) dùng tham số sẵn có của từng bộ công cụ. YOLO áp dụng cho mọi ảnh, nên DETR dùng `A.Affine` với `p=1`.
- **Phép ảnh** (độ sáng, nhoè, thời tiết, nhiễu, JPEG) chạy cùng một pipeline Albumentations `photometric_transforms()`. Với YOLO, gọi `patch_ultralytics()` hoặc `patch_yolov5()` trước khi huấn luyện để thay pipeline Albumentations mặc định (Blur, MedianBlur, ToGray, CLAHE xác suất 0,01) bằng pipeline chung. DETR dùng `detr_transforms()`.
- HSV của YOLO tắt hết (`hsv_h`, `hsv_s`, `hsv_v` bằng 0) vì độ sáng đã do pipeline chung đảm nhận và DETR không có phép tương ứng.
- Chốt `albumentations==2.0.8` trong extra `train`, vì tên tham số đổi giữa các phiên bản.

## Bảng tham số

| Phép biến đổi | Giá trị chung | YOLOv5 (`hyp.yaml`) | YOLOv8, RT-DETR (`ultralytics`) | DETR-R50 (`transformers`) |
| --- | --- | --- | --- | --- |
| Lật ngang | **Tắt** | `fliplr: 0.0` | `fliplr=0.0` | Không dùng `HorizontalFlip` |
| Lật dọc | **Tắt** | `flipud: 0.0` | `flipud=0.0` | Không dùng |
| Đổi sắc độ, độ bão hoà | **Tắt** | `hsv_h: 0.0`, `hsv_s: 0.0` | `hsv_h=0.0`, `hsv_s=0.0` | Không dùng |
| Độ sáng, độ tương phản | ±30%, p = 0,5 | `hsv_v: 0.0`; pipeline chung | `hsv_v=0.0`; pipeline chung | Pipeline chung |
| Nhoè chuyển động | Kernel 3–9 px, p = 0,2 | Pipeline chung | Pipeline chung | Pipeline chung |
| Mưa, sương mù | `RandomRain` hoặc `RandomFog` mặc định, p = 0,1 | Pipeline chung | Pipeline chung | Pipeline chung |
| Nhiễu | Gauss, độ lệch chuẩn 1–5% giá trị tối đa, p = 0,2 | Pipeline chung | Pipeline chung | Pipeline chung |
| Nén JPEG | Chất lượng 40–90, p = 0,3 | Pipeline chung | Pipeline chung | Pipeline chung |
| Co giãn | ±50%, cùng hệ số cho hai trục | `scale: 0.5` | `scale=0.5` | `Affine(scale=(0.5, 1.5), keep_ratio=True)` |
| Dịch chuyển | ±10% | `translate: 0.1` | `translate=0.1` | `Affine(translate_percent=(-0.1, 0.1))` |
| Xoay | ≤ 10° | `degrees: 10` | `degrees=10` | `Affine(rotate=(-10, 10))` |
| Viền sau phép hình học | Xám 114 | Mặc định 114 | Mặc định 114 | `Affine(fill=114)` |
| Cắt xiên, phối cảnh | **Tắt** | `shear: 0.0`, `perspective: 0.0` | `shear=0.0`, `perspective=0.0` | Không dùng |
| Mosaic, MixUp, CutMix, Copy-paste | **Tắt** trong lượt so sánh chính | `mosaic: 0.0`, `mixup: 0.0`, `copy_paste: 0.0` | `mosaic=0.0`, `mixup=0.0`, `cutmix=0.0`, `copy_paste=0.0` | Không dùng |

Docs §6 không quy định xác suất và mức nhiễu. Các giá trị p và độ lệch chuẩn ở trên là lựa chọn của nhánh huấn luyện YOLO, đặt thấp để ảnh gốc vẫn chiếm đa số. Ghi cách làm trong thẻ mô hình.

Lượt YOLOv8n tạm `yolov8n-640-v1.1-temp-s0` chạy trước khi có bảng này (`hsv_s=0.7`, `hsv_v=0.4`, Albumentations mặc định), nên không thuộc lượt so sánh chính.
