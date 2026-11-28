# Fixture đối chiếu Android

Bốn ảnh `vts_002564`, `vts_002610`, `vts_002711`, `vts_002712` thuộc test Signa v0.1. Ảnh nguồn VNTS của maitam, bản VQA của Anakonkai và tuyển chọn bởi tungitachi. Các ảnh ở đây được chuẩn hóa chiều, thu về cạnh dài 511 px, bỏ EXIF và lưu PNG RGB. Áp dụng CC BY-SA 4.0. [Ghi công dữ liệu v0.1](../../../../../../ai/data/sources/vnts-attribution.md) · [CC BY-SA 4.0](https://creativecommons.org/licenses/by-sa/4.0/).

`fixture.json` lưu SHA-256 của tensor đầu vào float32 little-endian, tham số letterbox và kết quả Python sau NMS. Mỗi `*.f32.gz` giữ kênh theo thứ tự tensor gốc, các anchor có điểm lớp cao nhất đạt ít nhất một phần tư ngưỡng điện thoại. Script xác nhận tập anchor rút gọn cho cùng kết quả decode như tensor đầy đủ.

Từ gốc kho, chạy với gói `signa-ai[data]` và `ai-edge-litert==1.2.0`. Truyền bốn ảnh từ thư mục ảnh test cục bộ.

```sh
python ai/scripts/export_android_vision_fixture.py \
  "$SIGNA_TEST_IMAGES/vts_002564.jpg" "$SIGNA_TEST_IMAGES/vts_002610.jpg" \
  "$SIGNA_TEST_IMAGES/vts_002711.jpg" "$SIGNA_TEST_IMAGES/vts_002712.jpg"
```
