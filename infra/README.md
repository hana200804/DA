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
mount tệp ở chế độ chỉ đọc và yêu cầu tệp đã tồn tại. Mặc định khi khởi
động, backend đồng bộ khi MySQL chưa có phiên bản nhãn hoặc tệp mới hơn.
Cùng phiên bản, backend thêm nhóm và loại còn thiếu, giữ các chỉnh sửa
của quản trị viên. Tệp cũ hơn được bỏ qua với một cảnh báo. Đặt
`SIGNA_CATALOG_IMPORT_ON_STARTUP=false` để tắt. Khi triển khai nhiều
bản sao backend, chỉ bật nạp trên một bản sao.

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
```

Chờ `migrate` thoát mã 0 và backend báo `UP` tại
`http://localhost:8080/actuator/health/readiness`. Script cần Bash, curl, jq,
Python 3 và dùng gateway `http://localhost:8080` theo mặc định. Truyền đúng
hai biến bootstrap đã có giá trị cuối qua môi trường của script. Có thể nhập
chúng trong Bash để mật khẩu không xuất hiện khi gõ hoặc trong lịch sử lệnh.

```bash
(
  IFS= read -r -p 'Email quản trị: ' SIGNA_BOOTSTRAP_ADMIN_EMAIL
  IFS= read -rs -p 'Mật khẩu quản trị: ' SIGNA_BOOTSTRAP_ADMIN_PASSWORD
  printf '\n'
  export SIGNA_BOOTSTRAP_ADMIN_EMAIL SIGNA_BOOTSTRAP_ADMIN_PASSWORD
  ./infra/smoke-test.sh
)
```

Script tạo hai tài khoản và một biển đã duyệt trong dữ liệu dev. Nếu cặp
đề xuất không được gom cụm, script có thể gửi cặp mới ở vị trí khác.
Nếu chạy cùng dịch vụ nhận diện, đặt `SIGNA_SMOKE_EXPECT_RECOGNITION_UNAVAILABLE=false`
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

## Chạy tài khoản Android qua USB trên Windows

Dùng override local để thử tài khoản thật mà không cần nạp checkpoint AI hay build web quản trị. Các container dùng project riêng `signa-android-local`; không dùng chung volume với project mặc định. Backend nghe tại `127.0.0.1:18080`.

```powershell
docker compose -p signa-android-local -f infra/compose.yaml -f infra/compose.android.yaml --profile app build backend
docker compose -p signa-android-local -f infra/compose.yaml -f infra/compose.android.yaml --profile app up -d --no-build backend
adb reverse tcp:18080 tcp:18080
```

Cần có `infra/.env` với mật khẩu riêng và `SIGNA_OTP_SECRET`. Không commit file này. Override dùng các cổng local: MySQL 13306, Redis 16379, MinIO 19000/19001, SMTP Mailpit 11025 và hộp thư 18025. Đăng ký không cần gửi email; quên mật khẩu dùng Mailpit tại `http://127.0.0.1:18025`.

Trong `mobile/local.properties`, giữ nguyên sdk.dir và các key bản đồ, thêm:

```properties
USE_FAKE_BACKEND=false
API_BASE_URL=http://127.0.0.1:18080/api/v1/
```

Build lại debug. `-Psigna.useFakeBackend` vẫn có ưu tiên cao hơn cấu hình local. Chỉ cấu hình này của máy đang thử được đổi sang backend thật; không đổi mặc định fake cho các máy khác. Cần chạy lại adb reverse sau khi ngắt/cắm USB hoặc đổi thiết bị; thêm `adb -s SERIAL` khi có nhiều thiết bị.

Tài khoản fake cũ không tự chuyển vào MySQL. Tạo tài khoản qua API/app thật. Việc cài APK dùng install -r, không xóa dữ liệu ứng dụng. Luồng hiện tại chỉ nghiệm thu tài khoản; nhận diện AI và đường dẫn ảnh/gateway cần cấu hình, kiểm tra riêng trước khi dùng.

Dừng riêng bộ container local, giữ dữ liệu:

```powershell
docker compose -p signa-android-local -f infra/compose.yaml -f infra/compose.android.yaml --profile app stop
```

### Nghiệm thu tài khoản Android local

- API: health 200, đăng ký 201, đăng nhập 200, lấy hồ sơ 200, đăng xuất 204; dùng lại token đã thu hồi nhận 401. Phiên MOBILE có TTL Redis 2.592.000 giây.
- Trên điện thoại Android 14 kết nối USB: đăng ký và đăng nhập bằng app, hiển thị đúng hồ sơ, mở lại ứng dụng khôi phục phiên, đăng xuất về màn xác thực. Đã đối chiếu tài khoản tồn tại trong MySQL và phiên Redis bị xóa khi app đăng xuất.
- APK dùng USE_FAKE_BACKEND=false; build debug, 54 unit test và lint không có lỗi. Đây là kết quả cho tài khoản; bản đồ, nhận diện AI và đồng bộ đề xuất chưa được nghiệm thu trong lượt này.
- Sau nghiệm thu, app được để ở màn đăng nhập để người dùng tạo tài khoản riêng. Mật khẩu/token kiểm thử không được ghi vào tài liệu hoặc Git.
