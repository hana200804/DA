# 05 · Dữ liệu và gán nhãn

## Mục lục

1. [Nguồn dữ liệu](#1-nguồn-dữ-liệu)
2. [Danh mục nhãn](#2-danh-mục-nhãn)
3. [Hướng dẫn gán nhãn](#3-hướng-dẫn-gán-nhãn)
4. [Video thực địa](#4-video-thực-địa)
5. [Phân chia tập dữ liệu](#5-phân-chia-tập-dữ-liệu)
6. [Tăng cường dữ liệu](#6-tăng-cường-dữ-liệu)
7. [Dữ liệu tổng hợp](#7-dữ-liệu-tổng-hợp)
8. [Mất cân bằng lớp](#8-mất-cân-bằng-lớp)
9. [Định dạng, phiên bản và lưu trữ](#9-định-dạng-phiên-bản-và-lưu-trữ)
10. [Kế hoạch và thống kê cần báo cáo](#10-kế-hoạch-và-thống-kê-cần-báo-cáo)

## 1. Nguồn dữ liệu

| Nguồn | Dùng cho | Ghi chú |
| --- | --- | --- |
| Bộ dữ liệu biển báo Việt Nam công khai (Roboflow Universe, Zalo AI Challenge, …) | Train, val, test | Kiểm tra giấy phép từng bộ trước khi dùng ([Đặc tả · Q3](02-dac-ta-yeu-cau.md#11-vấn-đề-cần-làm-rõ)); gán nhãn lại theo danh mục §2 |
| Video thực địa do nhóm tự quay | Train, val, test và **tập thực địa** | Nguồn chính cho điều kiện thực tế: điện thoại gắn trên xe, ban đêm, trời mưa |
| Ảnh chụp bằng điện thoại | Train, val, test | Đúng kiểu ảnh của FR-02: chụp gần, nhiều góc |
| Dữ liệu tổng hợp từ hình vẽ chuẩn QCVN 41 | Chỉ train | Bổ sung lớp hiếm (§7) |

Mọi nguồn được ghi trong manifest (§9) để truy được nguồn gốc từng ảnh. Đề xuất của người dùng **không** được dùng để huấn luyện trong phạm vi đồ án.

## 2. Danh mục nhãn

`ai/data/labels.yaml` là **nguồn duy nhất** của danh mục (AI-01). Từ file này sinh ra: file lớp cho huấn luyện, bảng `sign_categories` và `sign_types` trong CSDL, `labels.json` đóng gói trong ứng dụng.

- **Nhóm** — để hiển thị, lọc và gom cụm: `prohibitory`, `mandatory`, `warning`, `auxiliary`, `other`.
- **Lớp** — đầu ra của mô hình: mỗi lớp là một mã QCVN 41:2024/BGTVT, có chỉ số `id` cố định. Biển tốc độ tối đa thêm hậu tố giá trị: `P.127_50`. Biển phụ chỉ có một lớp `S` (không phân biệt nội dung).

```yaml
version: 1
categories:
  prohibitory: { name: Biển cấm }
  mandatory:   { name: Biển hiệu lệnh }
  warning:     { name: Biển nguy hiểm và cảnh báo }
  auxiliary:   { name: Biển phụ }
  other:       { name: Biển khác }
classes:
  - id: 0
    code: P.102
    category: prohibitory
    name: Cấm đi ngược chiều
  - id: 1
    code: P.123a
    category: prohibitory
    name: Cấm rẽ trái
  - id: 2
    code: P.127_50
    category: prohibitory
    name: Tốc độ tối đa cho phép 50 km/h
    speed_kmh: 50
  - id: 3
    code: R.303
    category: mandatory
    name: Nơi giao nhau chạy theo vòng xuyến
  - id: 4
    code: W.207a
    category: warning
    name: Giao nhau với đường không ưu tiên
  - id: 5
    code: S
    category: auxiliary
    name: Biển phụ
  - id: 6
    code: other_prohibitory
    category: prohibitory
    name: Biển cấm khác
```

Quy tắc:

- Mã và tên đối chiếu nguyên văn QCVN 41:2024/BGTVT.
- Dùng ít nhất 100 box trong train làm ngưỡng tách lớp. Chốt danh mục từ dữ liệu phát triển, gồm train và val. Gộp mã hiếm vào `other_<nhóm>`. Danh mục v1 dự kiến 40–60 lớp ([Đặc tả · Q1](02-dac-ta-yeu-cau.md#11-vấn-đề-cần-làm-rõ)).
- `id` không bao giờ được dùng lại cho mã khác. Thêm, bớt, gộp lớp làm tăng `version` và tăng `MAJOR` của mọi mô hình ([Thiết kế AI §8](04-thiet-ke-ai.md#8-phiên-bản-và-thẻ-mô-hình)).
- Biển lắp theo QCVN 41:2019 vẫn còn trên đường: dữ liệu gồm cả hai kiểu biển; mã khác nhau giữa hai phiên bản được ghi bảng đối chiếu trong `labels.yaml`.
- Loại biển chỉ quản trị viên nhập tay (mô hình không nhận diện) nằm trong CSDL với `label_id = NULL`, không có trong `labels.yaml`.

## 3. Hướng dẫn gán nhãn

Công cụ: CVAT (tự cài bằng Docker) hoặc Label Studio, xuất định dạng YOLO.

### Khoanh vùng

1. Box ôm sát mép ngoài của biển, gồm cả viền; không gồm cột.
2. Gán nhãn mọi biển có chiều cao ≥ 10 px trong ảnh gốc.
3. Mỗi biển trên cùng một cột có box riêng; biển phụ có box riêng, lớp `S`.
4. Biển bị che: vẫn gán nhãn nếu còn nhận ra mã, thêm thẻ `occluded`. Không nhận ra mã thì gán `other_<nhóm>`.

### Không gán nhãn

- Mặt sau của biển.
- Biển quay ngang so với hướng nhìn (lệch > 60°).
- Hình biển in trên quảng cáo, thân xe, phản chiếu qua kính.

### Thẻ thuộc tính

Ghi trong manifest, không nằm trong nhãn YOLO: `occluded`, `night`, `rain`, `blur`, `glare`.

### Kiểm soát chất lượng

- 10% ảnh được hai người gán nhãn độc lập.
- Đạt khi ≥ 95% box có IoU ≥ 0,8 và cùng mã giữa hai người.
- Mỗi bất đồng được giải quyết và, nếu cần, thêm thành một quy tắc mới trong mục này.

## 4. Video thực địa

| Hạng mục | Thiết lập |
| --- | --- |
| Gắn máy | Giữa kính lái, sau gương chiếu hậu, hướng thẳng, khung hình ngang |
| Ghi hình | 1080p, 30 FPS |
| GPS | Vị trí, tốc độ, hướng mỗi giây, đồng bộ dấu thời gian với video |
| Điều kiện | Ngày / đêm / mưa × nội đô / ngoại ô |
| Tuyến | 3–5 tuyến khác nhau, mỗi tuyến đi lặp ≥ 3 lượt |
| Nhãn chuẩn của tuyến | Danh sách biển thật trên mỗi tuyến: mã, vị trí, hướng — lập từ video và khảo sát |

Cách dùng:

- Gán các tuyến riêng cho train, val, test hoặc tập thực địa trước khi trích khung. Giữ mọi lượt qua cùng tuyến trong một tập. Trích khung ở 2 FPS để gán nhãn.
- Dùng video val để chọn cấu hình và hiệu chỉnh. Dùng video từ tập thực địa đã giữ riêng để đo FPS, tracker và sai số vị trí sau khi chốt cấu hình ([Kiểm thử §4–5](08-kiem-thu.md#4-đánh-giá-trên-thiết-bị)).
- Dùng các lượt đi qua tuyến giữ riêng để sinh đề xuất như nhiều người dùng khi đánh giá phê duyệt có trợ giúp ([Kiểm thử §6](08-kiem-thu.md#6-đánh-giá-phê-duyệt-có-trợ-giúp)).

Video gốc lưu riêng tư. Ảnh đưa vào báo cáo phải làm mờ khuôn mặt và biển số xe.

## 5. Phân chia tập dữ liệu

- Chia theo **chuỗi ảnh / tuyến đường**, không chia ngẫu nhiên từng khung, để các khung gần giống nhau không rơi vào cả train và test.
- Dữ liệu không thuộc tập thực địa: train / val / test = 70 / 15 / 15, phân tầng theo lớp ở mức chuỗi.
- Tập thực địa gồm các tuyến được giữ riêng, chỉ dùng để đánh giá.
- Hướng tới ít nhất 20 box mỗi lớp trong test. Báo cáo số box và lưu ý độ tin cậy của chỉ số ở lớp ít mẫu. Giữ nguyên danh mục đã chốt khi mở tập test.
- Kết quả chia ghi trong manifest, cố định theo phiên bản dữ liệu và dùng chung cho cả ba mô hình (AI-02).

## 6. Tăng cường dữ liệu

Cùng một danh sách cho cả ba mô hình. Mỗi bộ công cụ cấu hình theo tên tham số riêng, nhưng giá trị phải tương đương; bảng đối chiếu nằm trong `ai/configs/augment.md`.

| Được dùng | Tham số tham khảo |
| --- | --- |
| Thay đổi độ sáng, độ tương phản | ±30% |
| Nhoè chuyển động | Kernel 3–9 px |
| Mưa, sương mù giả lập | Albumentations `RandomRain`, `RandomFog` |
| Nhiễu, nén JPEG | Chất lượng 40–90 |
| Co giãn, dịch chuyển | ±50% tỉ lệ, ±10% dịch |
| Xoay nhẹ | ≤ 10° |
| Mosaic | Tắt trong lượt so sánh chính. Nếu thử riêng cho một mô hình, báo cáo riêng cấu hình và kết quả |

| Không được dùng | Lý do |
| --- | --- |
| Lật ngang | Đổi nghĩa biển: cấm rẽ trái thành cấm rẽ phải |
| Lật dọc, xoay > 10° | Biển thật không xuất hiện như vậy; một số biển đổi nghĩa |
| Đổi sắc độ mạnh | Màu là đặc trưng phân nhóm (đỏ cấm, xanh hiệu lệnh, vàng cảnh báo) |

## 7. Dữ liệu tổng hợp

1. Lấy hình vẽ chuẩn của mã biển hiếm theo QCVN 41.
2. Biến đổi ngẫu nhiên: phối cảnh, xoay ≤ 10°, tỉ lệ (chiều cao 16–200 px), ánh sáng, nhoè, nhiễu, che một phần.
3. Dán lên khung nền không có biển lấy từ video thực địa, đặt ở vị trí hợp lý (lề đường, phía trên); nhãn sinh tự động.
4. Tối đa 30% số box của mỗi lớp trong train; không bao giờ có trong val, test, tập thực địa.
5. Thí nghiệm có / không có dữ liệu tổng hợp được báo cáo riêng trên recall lớp hiếm.

## 8. Mất cân bằng lớp

- Gộp mã quá hiếm vào `other_<nhóm>` (§2).
- Bổ sung dữ liệu tổng hợp cho lớp hiếm (§7).
- Lấy mẫu lặp ảnh chứa lớp hiếm trong train (cùng hệ số cho cả ba mô hình).
- Luôn báo cáo chỉ số **theo từng lớp**, không chỉ trung bình.

## 9. Định dạng, phiên bản và lưu trữ

**Định dạng:** nhãn gốc ở định dạng YOLO (`class cx cy w h`, chuẩn hoá). Script `ai/src/signa_ai/dataset.py` sinh thêm COCO JSON cho DETR và cho bộ đánh giá chung ([Thiết kế AI §4](04-thiet-ke-ai.md#4-giao-thức-đánh-giá-và-chọn-mô-hình)).

| Thành phần | Nơi lưu | Có trong Git |
| --- | --- | --- |
| Ảnh, video | MinIO bucket `datasets` (riêng tư) hoặc ổ chung của nhóm | Không |
| Manifest `ai/data/manifests/vX.Y.csv` | Repo | Có |
| Danh mục nhãn `ai/data/labels.yaml` | Repo | Có |
| Nhật ký thay đổi `ai/data/CHANGELOG.md` | Repo | Có |

Phiên bản dữ liệu `vMAJOR.MINOR`: tăng `MAJOR` khi đổi danh mục nhãn, tăng `MINOR` khi thêm hoặc sửa ảnh.

| Cột manifest | Ý nghĩa |
| --- | --- |
| `path` | Đường dẫn ảnh |
| `sha256` | Băm nội dung để phát hiện thay đổi |
| `split` | `train`, `val`, `test`, `field` |
| `source` | `public:<tên bộ>`, `field:<mã tuyến>`, `photo`, `synthetic` |
| `sequence` | Mã chuỗi ảnh hoặc tuyến, dùng để chia tập |
| `tags` | Thẻ thuộc tính, cách nhau bằng `;` |
| `label_map_version` | Phiên bản danh mục nhãn |

## 10. Kế hoạch và thống kê cần báo cáo

| Tuần | Đầu ra |
| --- | --- |
| 1–2 | Danh mục nhãn v1 (bản nháp), danh sách bộ dữ liệu công khai đã kiểm tra giấy phép |
| 3–4 | Dữ liệu công khai gán nhãn lại; quay video thực địa đợt 1 |
| 5 | **Dữ liệu v1.0**: manifest, chia tập, chốt danh mục |
| 6–9 | Video thực địa đợt 2 (đêm, mưa); tập thực địa; dữ liệu tổng hợp |

Thống kê đưa vào báo cáo:

- Số ảnh, số box theo lớp và theo nhóm, trong từng tập.
- Phân bố kích thước box: nhỏ (< 32² px), vừa, lớn (> 96² px).
- Phân bố theo điều kiện: ngày, đêm, mưa; theo nguồn.
- Kết quả kiểm soát chất lượng gán nhãn.
