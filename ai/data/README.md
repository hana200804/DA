# Chuẩn bị dữ liệu cho Signa

| Đường dẫn dưới `ai/data/` | Nội dung | Trong Git |
| --- | --- | --- |
| [`labels.yaml`](labels.yaml) | Danh mục nhãn v1, nguồn duy nhất | Có |
| [`labels-v0.yaml`](labels-v0.yaml) | Danh mục mẫu v0, chỉ để dựng lại bộ chạy thử v0.1 | Có |
| [`CHANGELOG.md`](CHANGELOG.md) | Lịch sử phiên bản dữ liệu | Có |
| [`sources/`](sources/README.md) | Nguồn, giấy phép, ghi công và cách dựng từng bộ công khai | Có |
| `reviews/` | Bảng rà nhãn thủ công, ví dụ biến thể `W.207` của VN82 | Có |
| `manifests/` | Manifest `vX.Y.csv` có SHA-256 từng ảnh | Có |
| `templates/` | Mẫu CSV thu dữ liệu thực địa | Có |
| [`synthetic/templates.csv`](synthetic/templates.csv) | Nguồn, giấy phép và SHA-256 của hình vẽ biển báo | Có |
| `raw/` | Tệp tải về, giữ nguyên | Không |
| `processed/` | Bộ đã xuất YOLO và COCO, dựng lại được từ `raw/` | Không |

## Bộ dữ liệu v1.1

Dùng bộ này để huấn luyện và so sánh ba mô hình. Ảnh lấy từ [VN82](sources/vn82.md), nhãn theo [danh mục v1](labels.yaml) gồm 46 lớp.

| Tập | Ảnh | Box |
| --- | ---: | ---: |
| Train | 6.679 | 13.283 |
| Val | 1.599 | 2.685 |
| Test | 1.700 | 2.664 |

- [Bảng ánh xạ](sources/vn82-to-signa.csv) đổi 82 lớp VN82 sang mã QCVN 41:2024. Lớp nào dưới 100 box train được gộp vào `other_<nhóm>`. Box đèn tín hiệu bị bỏ, ảnh vẫn giữ. [Bảng sửa nhãn](reviews/vn82-box-fixes.csv) đổi 48 box ghi nhầm `W.202` thành `P.127_60`.
- Tập chia gốc của VN82 rò rỉ khung hình gần trùng giữa train, val và test. Script gom mọi ảnh cùng cảnh vào một nhóm rồi chia theo nhóm, cân bằng số box từng lớp. Hai ảnh cùng cảnh khi dHash 256 bit lệch tối đa 60, hoặc lệch tối đa 80 kèm tương quan ảnh xám 32×24 từ 0,88. Hai ảnh liền số chỉ cần dHash lệch tối đa 70. Kết quả có 2.108 nhóm và không còn cặp cùng cảnh nào nằm khác tập. `source-audit.json` ghi lại các số này.
- `P.124c` chỉ có 10 box val và 9 box test vì phần lớn box nằm chung một nhóm lớn. AP của lớp này dao động nhiều.
- Test vẫn cùng nguồn VN82 với train. Cần thêm tập test từ nguồn khác để đo khả năng tổng quát.

Chạy từ thư mục `ai/` sau khi đã [dựng VN82](sources/vn82.md#dựng-lại):

```sh
uv run --no-sync python scripts/prepare_vn82_v1.py \
  --source data/processed/vn82-colab --output data/processed/v1.1
(cd data/processed && zip -q -r signa-v1.1.zip v1.1)
```

Trên Colab, làm theo [hướng dẫn VN82](sources/vn82.md#bắt-đầu-trên-colab) với `signa-v1.1.zip` và thư mục `/content/v1.1`.

## Bộ dữ liệu tổng hợp v1.1-syn

Công cụ AI-07 chạy trên CPU với Pillow và NumPy. Mặc định, lớp hiếm có **dưới 150 box** trong `train.by_class` của `processed/v1.1/stats.json` và nằm ngoài nhóm mã `other_*`. Tùy chọn `--threshold` nhận ngưỡng cho cả bước tải hình vẽ và bước sinh.

| Mã lớp hiếm | Box thật trong train v1.1 | Box dán với seed 0 | Giới hạn box dán |
| --- | ---: | ---: | ---: |
| `I.409` | 148 | 63 | 63 |
| `P.104` | 110 | 47 | 47 |
| `P.123a` | 142 | 60 | 60 |
| `P.124a` | 144 | 61 | 61 |
| `P.124d` | 109 | 46 | 46 |
| `P.135` | 133 | 57 | 57 |
| `R.420` | 115 | 49 | 49 |
| `W.203a` | 104 | 44 | 44 |
| `W.203c` | 121 | 51 | 51 |
| `W.208` | 107 | 45 | 45 |
| `W.220` | 113 | 48 | 48 |
| `W.239b` | 111 | 47 | 47 |

Mỗi lớp nhận tối đa `floor(box thật × 3 / 7)` box dán, tương ứng 30% tổng box thật và box dán. Ảnh tổng hợp giữ nhãn của nền. Thẻ `synthetic_boxes=1` trong `tags` đánh dấu box cuối là biển dán. Các box chép từ nền vẫn có trong YOLO và COCO, còn quota dựa trên số box từ train v1.1 gốc.

Script lấy PNG do Wikimedia Commons render từ SVG biển báo Việt Nam theo QCVN 41. API `imageinfo` cung cấp URL và `extmetadata` cung cấp thông tin ghi công. Công cụ chấp nhận PD, CC0, CC BY và CC BY-SA, ghi lý do loại tệp ra stderr. Cả 12 mã trên có hình vẽ, với giấy phép CC BY-SA 4.0 và tác giả ghi là Government of Vietnam. `P.124a` dùng biến thể cấm quay đầu sang trái `P124a1`. Bảng CSV ghi tên tệp Commons và SHA-256 của từng PNG. Các PNG chỉ lưu trong `processed/synthetic-templates/`. [Bảng nguồn](synthetic/templates.csv) · [SVG của P.124a](https://commons.wikimedia.org/wiki/File:Vietnam_road_sign_P124a1.svg) · [CC BY-SA 4.0](https://creativecommons.org/licenses/by-sa/4.0/)

Do chưa có video thực địa, v1.1-syn chỉ dùng ảnh train v1.1 làm nền. Công cụ ưu tiên ảnh 0 box rồi đến ảnh 1 box và dùng mỗi ảnh nền tối đa một lần. Tùy chọn `--max-background-box-height` mặc định là `0.25`, loại ảnh có box gốc cao hơn 25% chiều cao ảnh. Công cụ lấy `sequence` từ manifest, đặt biển vào hai bên ở nửa trên ảnh, tránh dải đen đóng khung và chừa lề quanh box gốc.

Biển được biến đổi phối cảnh nhẹ, xoay tối đa 10°, có chiều cao 16–200 px, thay đổi sáng và tương phản, làm nhoè, thêm nhiễu và có thể bị che một phần. Để giảm chênh lệch độ nét với ảnh nền, công cụ thu nhỏ biển theo hệ số ngẫu nhiên 0,45–0,75 rồi phóng lại trước khi thêm nhiễu. Biển giữ hướng và sắc độ.

Chạy từ thư mục `ai/` sau khi đã dựng v1.1:

```sh
uv run --extra data python scripts/download_synthetic_templates.py
uv run --extra data python -m signa_ai.synthetic generate --seed 0
uv run --extra data python -m signa_ai.synthetic package
(cd data/processed && sha256sum -c signa-v1.1-syn.sha256)
```

Dùng cùng ảnh nguồn, hình vẽ, phiên bản Pillow và NumPy, tham số và seed để tái tạo từng byte của ảnh, nhãn và CSV sinh ra. Dùng `generate --backgrounds <thư-mục>` khi có khung hình đã xác nhận không có biển từ video dành riêng cho train. Xếp ảnh theo `<thư-mục>/<sequence>/<tên-ảnh>` và dùng mã chuỗi tuyến đã chốt cho tập train. Công cụ đối chiếu chuỗi và SHA-256 với val, test và field của manifest gốc.

`generate` ghi ảnh, nhãn, `intake.csv` và `generation.json` vào `processed/v1.1-syn-intake/`. `package` ghép với v1.1 qua pipeline YOLO/COCO, sao chép từng byte ảnh, nhãn YOLO và JSON COCO của val, test và field. Tệp `ATTRIBUTION.md` trong gói ghi công 12 hình vẽ từ Commons và điều khoản chia sẻ tác phẩm phái sinh. Cấu hình YOLO trong gói có đường dẫn tuyệt đối như v1.1.

### Kết quả seed 0

Bộ lọc loại **782 ảnh train** có box cao hơn 25% chiều cao ảnh. Lượt này dùng **196 nền 0 box** và **422 nền 1 box** để tạo **618 ảnh tổng hợp**. Train có **7.297 ảnh và 14.323 box**, gồm 13.283 box gốc, 618 box dán và **422 box thật chép thêm**. So sánh có và không có dữ liệu tổng hợp phải nêu số box thật chép thêm theo từng lớp vì việc lặp lại nhãn thật cũng có thể ảnh hưởng kết quả.

| Mã lớp | Box thật chép thêm |
| --- | ---: |
| `I.407a` | 4 |
| `I.409` | 25 |
| `I.423` | 2 |
| `I.434a` | 1 |
| `P.103a` | 12 |
| `P.103c` | 8 |
| `P.104` | 6 |
| `P.105` | 1 |
| `P.106a` | 5 |
| `P.123a` | 18 |
| `P.123b` | 6 |
| `P.124c` | 2 |
| `P.127_50` | 2 |
| `P.127_80` | 10 |
| `P.130` | 16 |
| `P.131a` | 19 |
| `P.135` | 4 |
| `P.137` | 10 |
| `R.303` | 6 |
| `R.415a` | 1 |
| `R.420` | 17 |
| `W.201a` | 7 |
| `W.202` | 2 |
| `W.203a` | 8 |
| `W.203c` | 9 |
| `W.207` | 7 |
| `W.208` | 2 |
| `W.210` | 23 |
| `W.220` | 15 |
| `W.224` | 2 |
| `W.225` | 9 |
| `W.239b` | 1 |
| `W.245a` | 11 |
| `other_indication` | 1 |
| `other_mandatory` | 31 |
| `other_prohibitory` | 77 |
| `other_warning` | 42 |
| **Tổng** | **422** |

Tệp `processed/signa-v1.1-syn.sha256` ghi hai SHA-256 của lượt dựng này.

| Tệp | SHA-256 |
| --- | --- |
| `signa-v1.1-syn.zip` | `49e086b85446db1006d25c3824f6a72449cd93d98854c62408529b4439a7fcba` |
| `v1.1-syn/manifest.csv` | `24d86e904f38fd6860c85c7fe69b9414355105b157e0b5d22e9c0d467398f1cb` |

`processed/v1.1-syn/preview/` chứa 6 ảnh xem mẫu có box vẽ sẵn, với màu xanh cho biển dán và màu vàng cho box gốc.

## Bộ chạy thử v0.1

Pipeline dùng [danh mục nhãn v0](labels-v0.yaml). Ảnh và nhãn được lưu cục bộ theo quy ước của [thiết kế dữ liệu](../../docs/05-du-lieu.md#9-định-dạng-phiên-bản-và-lưu-trữ). Xem [nguồn và giấy phép](sources/README.md) trước khi chia sẻ dữ liệu.

| Đường dẫn dưới `ai/data/` | Nội dung |
| --- | --- |
| `processed/v0.1/data.yaml` | Cấu hình ảnh và lớp cho YOLOv5, YOLOv8. `path` là đường dẫn tuyệt đối, cần cập nhật nếu chuyển gói sang máy khác. |
| `processed/v0.1/coco/{train,val,test,field}.json` | Nhãn COCO cho DETR và bộ đánh giá. Mở `file_name` tương đối từ thư mục `v0.1/`. |
| `processed/v0.1/manifest.csv` | Mã nguồn ảnh, nhóm, tập, số phiên bản nhãn và SHA-256 từng ảnh. Bản sao trong Git nằm ở [manifests/v0.1.csv](manifests/v0.1.csv). |
| `processed/v0.1/stats.json` | Số ảnh và box theo tập, lớp, nhóm, nguồn và kích thước. |
| `processed/vnts-01/review/` | 359 ảnh nguồn, nhãn gốc `objects.jsonl`, nhãn COCO theo mã nguồn và số box từng mã. Mở `file_name` của COCO tương đối từ `review/images/`. |
| `processed/vnts-01/source-audit.json` | Số ảnh giữ, số ảnh loại và SHA-256 ba tệp nguồn. |

Từ 359 ảnh có 776 box trong nguồn đã lọc trùng, pipeline chọn 90 ảnh mà mã của mọi box trong nhãn nguồn đều ánh xạ được vào v0. `W.207` và các mã ngoài danh mục v0 được giữ ở bộ rà soát. Tập test của bộ chạy thử lấy từ test của nguồn. Do nguồn thiếu mã tuyến, validation lấy từ train của nguồn theo các dải 100 số trong tên ảnh gốc. Các dải chỉ là nhóm xấp xỉ. Dùng v0.1 để chạy thử luồng huấn luyện, xuất kết quả và đánh giá. Kết quả so sánh chính thức dùng bộ v1.1 ở trên.

| Tập | Ảnh | Box |
| --- | ---: | ---: |
| Train | 46 | 80 |
| Val | 9 | 18 |
| Test | 35 | 78 |

## Tạo lại bộ chạy thử

Chạy từ thư mục `ai/`. Bỏ qua bước tải nếu ba tệp đã có trong `data/raw/`. Script xác minh SHA-256 của cả ba tệp trước khi trích dữ liệu.

```sh
uv venv --python 3.11
uv pip install -e '.[vnts]'
mkdir -p data/raw
revision=04a6b91bef3f92c729f0ea7c383a1e3dbfece82d
base="https://huggingface.co/datasets/tungitachi/vietnamese-traffic-sign-vqa-dedup-1k/resolve/$revision"
curl -fL "$base/metadata/objects.jsonl" -o data/raw/vnts-dedup-objects.jsonl
for split in train test; do
  curl -fL "$base/data/$split-00000-of-00001.parquet" -o "data/raw/vnts-dedup-$split.parquet"
done
uv run --no-sync python scripts/prepare_vnts_pilot.py \
  --raw data/raw --output data/processed/vnts-01
uv run --no-sync python -m signa_ai.dataset build \
  --root data/processed/vnts-01/intake \
  --intake data/processed/vnts-01/intake/intake.csv \
  --labels data/labels-v0.yaml --output data/processed/v0.1
cp data/sources/vnts-attribution.md data/processed/v0.1/ATTRIBUTION.md
```

Mỗi lần xuất cần thư mục đích mới. Đổi tên thư mục kết quả cũ nếu cần tạo lại đúng đường dẫn trên.

## Bổ sung ảnh, video và nhãn chuẩn

1. Ghi tuyến, lượt quay, điều kiện và tập vào [field-trips.csv](templates/field-trips.csv). Dùng cùng `route_id` cho các lượt đi trên cùng tuyến.
2. Ghi vị trí, tốc độ và hướng theo UTC mỗi giây vào [gps.csv](templates/gps.csv). Dùng `start_utc` của lượt quay để gắn từng khung hình với GPS.
3. Trích ảnh ở 2 FPS và gán nhãn theo [hướng dẫn](../../docs/05-du-lieu.md#3-hướng-dẫn-gán-nhãn). Lưu ảnh ở `images/` và nhãn cùng tên ở `labels/`. Ảnh âm có tệp nhãn rỗng.
4. Ghi danh sách biển chuẩn đã khảo sát vào [field-signs.csv](templates/field-signs.csv). Ghi phép gán nhãn độc lập trên 10% ảnh vào [annotation-qc.csv](templates/annotation-qc.csv). Mục tiêu là ít nhất 95% box khớp mã và có IoU từ 0,8.
5. Tạo CSV từ [intake.csv](templates/intake.csv). Điền đường dẫn ảnh tương đối, nguồn như `field:T01` hoặc `public:<tên bộ>` và `sequence` chung cho cả tuyến hoặc chuỗi ảnh. Có thể cố định `split` là `train`, `val`, `test` hoặc `field`. Tách các thẻ trong cột `tags` bằng dấu chấm phẩy. Ảnh tổng hợp dùng `source=synthetic`. Pipeline đưa chúng vào train và kiểm tra tỷ lệ tối đa 30% box mỗi lớp.

```sh
uv pip install -e '.[data]'
uv run --no-sync python -m signa_ai.dataset build \
  --root data/raw/team-input --intake data/raw/team-input/intake.csv \
  --output data/processed/team-draft
```

Lệnh `python -m signa_ai.dataset coco-to-yolo` nhận `--coco`, `--labels` và `--output`. ID và mã lớp COCO phải khớp danh mục dùng để xuất.

Trước khi chốt v1, đối chiếu mã và tên với QCVN, đếm box từ train và val, giữ lớp riêng khi đạt ngưỡng theo thiết kế và xử lý các mã còn mơ hồ khi gán nhãn. [Q1 và Q3](../../docs/02-dac-ta-yeu-cau.md#11-vấn-đề-cần-làm-rõ) · [Tiêu chí dữ liệu](../../docs/05-du-lieu.md#2-danh-mục-nhãn)
