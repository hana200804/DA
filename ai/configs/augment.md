# Bảng đối chiếu tăng cường dữ liệu

Danh sách phép biến đổi lấy từ [Dữ liệu §6](../../docs/05-du-lieu.md#6-tăng-cường-dữ-liệu). Ba mô hình dùng **cùng giá trị** trong lượt so sánh chính. Mỗi bộ công cụ đặt tên tham số khác nhau, nên bảng này ghi tên tham số tương ứng.

Nhánh huấn luyện YOLO và DETR điền các cột còn trống. Khi đổi một giá trị, sửa cả ba cột trong cùng pull request.

| Phép biến đổi | Giá trị chung | YOLOv5 (`hyp.yaml`) | YOLOv8, RT-DETR (`ultralytics`) | DETR-R50 (`transformers`) |
| --- | --- | --- | --- | --- |
| Lật ngang | **Tắt** | `fliplr: 0.0` | `fliplr=0.0` | Không dùng `RandomHorizontalFlip` |
| Lật dọc | **Tắt** | `flipud: 0.0` | `flipud=0.0` | Không dùng |
| Đổi sắc độ | **Tắt** hoặc rất nhỏ | `hsv_h: 0.0` | `hsv_h=0.0` | Không dùng |
| Độ sáng, độ tương phản | ±30% | | | |
| Nhoè chuyển động | Kernel 3–9 px | | | |
| Mưa, sương mù | Albumentations `RandomRain`, `RandomFog` | | | |
| Nhiễu, nén JPEG | Chất lượng 40–90 | | | |
| Co giãn | ±50% | `scale: 0.5` | `scale=0.5` | |
| Dịch chuyển | ±10% | `translate: 0.1` | `translate=0.1` | |
| Xoay | ≤ 10° | `degrees: 10` | `degrees=10` | |
| Mosaic | **Tắt** trong lượt so sánh chính | `mosaic: 0.0` | `mosaic=0.0` | Không dùng |

Các phép không có sẵn trong bộ công cụ thì chạy bằng Albumentations với cùng tham số. Ghi rõ cách làm trong thẻ mô hình.
