# Triển khai Signa với Docker Compose

Dùng Docker Compose 2.24.4 trở lên và BuildKit. Chạy các lệnh sau từ gốc repo.

## Chuẩn bị

```sh
cp infra/.env.example infra/.env
```

Đổi các mật khẩu và khoá truy cập mẫu trong `infra/.env`. Đặt
`SIGNA_BOOTSTRAP_ADMIN_EMAIL` và `SIGNA_BOOTSTRAP_ADMIN_PASSWORD` nếu cần tạo
quản trị viên đầu tiên. Giữ `infra/.env` ngoài Git.

Máy chạy Compose, kể cả demo, cần có `ai/data/labels.yaml` từ repo. Compose
mount tệp ở chế độ chỉ đọc và yêu cầu tệp đã tồn tại. Backend mặc định nạp
danh mục lên MySQL khi khởi động. Đặt `SIGNA_CATALOG_IMPORT_ON_STARTUP=false`
để tắt. Khi triển khai nhiều bản sao backend, chỉ bật nạp trên một bản sao.

Compose mặc định bật worker gom cụm cho backend ở các profile `app`, `dev`
và `demo`. Đặt `SIGNA_CLUSTERING_WORKER_ENABLED=false` trong `infra/.env`
để tắt. Chỉ bật worker trên một bản sao backend trong mỗi môi trường theo
[ADR-0007](../docs/adr/0007-gom-cum-tuan-tu-qua-redis-stream.md). Tự duyệt
vẫn mặc định tắt.

Khi khởi tạo volume MySQL, script dùng `SIGNA_DB_MIGRATOR_USER` và
`SIGNA_DB_APP_USER` cùng với các mật khẩu tương ứng. Hai tên phải khác nhau,
khác `root` và có 1–32 ký tự trong tập `A–Z`, `a–z`, `0–9`, `_`, `.`, `-`.
Với volume đã tạo, cần đổi tài khoản trong MySQL trước khi đổi cấu hình kết nối.

Tải ba checkpoint của nhóm theo [hướng dẫn nhận diện](../ai/README.md#ba-mô-hình).
Đặt chúng cùng thẻ mô hình tương ứng trong `ai/models/` hoặc cấu hình
`SIGNA_RECOGNITION_MODELS_DIR` trong `infra/.env` để dùng thư mục khác.
Đường dẫn tương đối tính từ `infra/`. Compose mount thư mục ở chế độ chỉ
đọc. Checkpoint chỉ lưu ngoài Git và ngoài image.

| Thư mục con | Tệp bắt buộc |
| --- | --- |
| `yolov5s/` | `best.pt`, `model_card.json` |
| `yolov8s/` | `best.pt`, `model_card.json` |
| `detr-r50/` | `best.pt`, `model_card.json` |

Ảnh nhận diện dùng PyTorch CPU và chạy một tiến trình Uvicorn. Backend
khởi động khi container nhận diện đã chạy. Healthcheck `/healthz` đạt khi
cả ba mô hình đã nạp xong. Dùng `docker compose -f infra/compose.yaml logs recognition`
để xem lỗi nạp và `docker compose -f infra/compose.yaml ps` để xem trạng thái.

## HTTP local

```sh
docker compose -f infra/compose.yaml --profile dev up -d --build
```

Mở `http://localhost:8080`. Gateway chuyển `/api/v1/`, `/actuator/health` và
`/actuator/health/readiness` tới backend. Các đường dẫn Actuator còn lại
trả `404`. Gateway chuyển
`/icons/` tới bucket công khai `sign-icons`, các URL ký của
`sign-crops` và `report-photos` tới MinIO, và các đường dẫn còn lại tới web quản trị.
MinIO xác thực chữ ký và thời hạn của ảnh riêng tư.

Nếu cần MySQL, Redis hoặc MinIO trên host khi chạy các thành phần riêng,
có thể chỉ dựng ba dịch vụ này. Các cổng của chúng chỉ bind `127.0.0.1`.

```sh
docker compose -f infra/compose.yaml up -d mysql redis minio
```

| Dịch vụ | Cổng trên host ở môi trường dev |
| --- | --- |
| MySQL | 3306 |
| Redis | 6379 |
| MinIO | 9000, console 9001 |

Có thể dùng profile `app` như tên tương đương với `dev` để chạy cả hệ thống.
Profile `migrate` dành cho lệnh Flyway chạy một lần. Dịch vụ nhận diện
nằm trong mạng Docker riêng cùng backend và chỉ nghe cổng 8000 trong mạng đó.

### Kiểm tra nhanh backend

Đặt `SIGNA_BOOTSTRAP_ADMIN_EMAIL` và `SIGNA_BOOTSTRAP_ADMIN_PASSWORD` trong
`infra/.env` trước khi dựng stack dev để có tài khoản quản trị cho smoke test.

Khi thiếu checkpoint, lưu cấu hình sau vào một tệp tạm ngoài repo để chạy
dev mà không build dịch vụ nhận diện.

```yaml
services:
  backend:
    depends_on:
      recognition:
        required: false
  recognition:
    profiles: !override [recognition]
```

```sh
docker compose -f infra/compose.yaml -f /tmp/signa-no-recognition.yaml --profile dev up -d --build
docker compose -f infra/compose.yaml -f /tmp/signa-no-recognition.yaml --profile dev ps -a migrate
set -a
. ./infra/.env
set +a
./infra/smoke-test.sh
```

Chờ `migrate` thoát mã 0 và backend báo `UP` tại
`http://localhost:8080/actuator/health/readiness`. Script cần Bash, curl, jq,
Python 3 và dùng gateway `http://localhost:8080` theo mặc định. Script tạo hai
tài khoản, hai đề xuất và một biển đã duyệt trong dữ liệu dev. Nếu chạy cùng
dịch vụ nhận diện, đặt `SIGNA_SMOKE_EXPECT_RECOGNITION_UNAVAILABLE=false`
để bỏ qua phép thử phản hồi `503`.

## Demo HTTPS

Đặt `fullchain.pem` và `privkey.pem` trong `infra/certs/`. Tên miền hoặc
địa chỉ IP trên chứng chỉ phải khớp origin HTTPS mà điện thoại và trình
duyệt dùng. Đặt `SIGNA_DEMO_PUBLIC_ORIGIN` trong `infra/.env` thành
origin đó để backend ký URL ảnh. Giữ khoá TLS ngoài Git.

```sh
docker compose -f infra/compose.yaml -f infra/compose.demo.yaml --profile demo up -d --build
```

Override demo gỡ các cổng hạ tầng trên host và thay cổng HTTP bằng HTTPS
trên cổng 443. Bản demo cấp tối đa 6,5 GiB RAM cho các dịch vụ chạy lâu
dài trên máy tham chiếu 4 vCPU, 8 GB RAM. Các dịch vụ này tự khởi động lại
theo `unless-stopped`. Flyway chạy một lần và thoát trước khi backend chạy.

Xem [kịch bản k6](../backend/src/test/k6/README.md) để kiểm thử sau khi
nạp dữ liệu mẫu và khi nhận diện đã sẵn sàng.
