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
| `raw/` | Tệp tải về, giữ nguyên | Không |
| `processed/` | Bộ đã xuất YOLO và COCO, dựng lại được từ `raw/` | Không |

## Bộ dữ liệu v1.0

Dùng bộ này để huấn luyện và so sánh ba mô hình. Ảnh lấy từ [VN82](sources/vn82.md), nhãn theo [danh mục v1](labels.yaml) gồm 46 lớp.

| Tập | Ảnh | Box | Nhóm ảnh |
| --- | ---: | ---: | ---: |
| Train | 6.673 | 13.256 | 922 |
| Val | 1.615 | 2.702 | 598 |
| Test | 1.690 | 2.674 | 588 |

- [Bảng ánh xạ](sources/vn82-to-signa.csv) đổi 82 lớp VN82 sang mã QCVN 41:2024. Lớp nào dưới 100 box train được gộp vào `other_<nhóm>`. Box đèn tín hiệu bị bỏ, ảnh vẫn giữ.
- Tập chia gốc của VN82 rò rỉ khung hình gần trùng giữa train, val và test. Script gom mọi ảnh cùng cảnh vào một nhóm rồi chia theo nhóm, cân bằng số box từng lớp. Hai ảnh cùng cảnh khi dHash 256 bit lệch tối đa 60, hoặc lệch tối đa 80 kèm tương quan ảnh xám 32×24 từ 0,88. Hai ảnh liền số chỉ cần dHash lệch tối đa 70. Kết quả có 2.108 nhóm và không còn cặp cùng cảnh nào nằm khác tập. `source-audit.json` ghi lại các số này.
- `P.124c` chỉ có 10 box val và 9 box test vì phần lớn box nằm chung một nhóm lớn. AP của lớp này dao động nhiều.
- Test vẫn cùng nguồn VN82 với train. Cần thêm tập test từ nguồn khác để đo khả năng tổng quát.

Chạy từ thư mục `ai/` sau khi đã [dựng VN82](sources/vn82.md#dựng-lại):

```sh
uv run --no-sync python scripts/prepare_vn82_v1.py \
  --source data/processed/vn82-colab --output data/processed/v1.0
(cd data/processed && zip -q -r signa-v1.0.zip v1.0)
```

Trên Colab, làm theo [hướng dẫn VN82](sources/vn82.md#bắt-đầu-trên-colab) với `signa-v1.0.zip` và thư mục `/content/v1.0`.

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

Từ 359 ảnh có 776 box trong nguồn đã lọc trùng, pipeline chọn 90 ảnh mà mã của mọi box trong nhãn nguồn đều ánh xạ được vào v0. `W.207` và các mã ngoài danh mục v0 được giữ ở bộ rà soát. Tập test của bộ chạy thử lấy từ test của nguồn. Do nguồn thiếu mã tuyến, validation lấy từ train của nguồn theo các dải 100 số trong tên ảnh gốc. Các dải chỉ là nhóm xấp xỉ. Dùng v0.1 để chạy thử luồng huấn luyện, xuất kết quả và đánh giá. Kết quả so sánh chính thức cần tập dữ liệu v1.0 và tuyến giữ riêng.

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
