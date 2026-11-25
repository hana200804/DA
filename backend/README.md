# Signa backend

Spring Boot một khối chia module (`identity`, `catalog`, `sign`, `recognition`, `audit`) theo [ADR-0005](../docs/adr/0005-backend-mot-khoi-chia-module.md) và [thiết kế triển khai](../docs/backend/thiet-ke-trien-khai.md).

## Phiên bản đã chọn

| Thành phần | Phiên bản |
| --- | --- |
| Java | 21 (Temurin) |
| Gradle | 9.9.2 (wrapper) |
| Spring Boot | 4.2.0 |
| springdoc-openapi | 3.2.1 |
| Flyway | 12 (theo BOM Spring Boot) |
| MinIO Java SDK | 9.0.4 |
| Testcontainers | 2.0.5 |
| ArchUnit | 1.5.1 |
| MySQL | 8.4.13 |
| Redis | 7.4.12 |
| MinIO server | `pgsty/minio:RELEASE.2026-08-04T00-00-00Z` |
| SMTP khi phát triển | [`axllent/mailpit:v1.34.0`](https://github.com/axllent/mailpit/releases/tag/v1.34.0) |

Ảnh `minio/minio` chính thức không còn công khai trên Docker Hub và quay.io, nên Compose và test dùng `pgsty/minio`, bản cộng đồng giữ nguyên dòng lệnh và API của MinIO.

## Yêu cầu

- JDK 21. Gradle tự tải qua `./gradlew`.
- Docker, chỉ cần cho `integrationTest` và để chạy ứng dụng với hạ tầng thật.

## Kiểm thử

Chạy trong thư mục `backend/`:

```sh
./gradlew spotlessApply          # định dạng mã (google-java-format)
./gradlew test                   # unit và test kiến trúc, không cần Docker
./gradlew integrationTest        # các lớp *IT, cần Docker (Testcontainers)
./gradlew jacocoTestReport       # build/reports/jacoco/test/html
```

`test` bỏ qua `**/*IT.class`; `integrationTest` chỉ chạy các lớp đó; `check` chạy cả hai. Test tích hợp kế thừa `vn.signa.support.IntegrationTest`, dùng chung một bộ container MySQL, Redis, MinIO và một Spring context. Container MySQL chạy cùng script [`infra/mysql/init/01-users.sh`](../infra/mysql/init/01-users.sh) với Compose. CI ([`backend.yml`](../.github/workflows/backend.yml)) chạy `spotlessCheck test integrationTest jacocoTestReport` và gitleaks.

## Chạy trên máy

Từ gốc repo, dựng MySQL, Redis, MinIO và Mailpit:

```sh
cp infra/.env.example infra/.env   # rồi đổi mọi mật khẩu
docker compose -f infra/compose.yaml up -d
```

Cổng chỉ mở trên `127.0.0.1`: MySQL 3306, Redis 6379, MinIO 9000 (console 9001) và Mailpit 1025 (hộp thư web 8025). Script `01-users.sh` chỉ chạy khi volume MySQL được tạo lần đầu. Đổi mật khẩu sau đó thì xoá volume: `docker compose -f infra/compose.yaml down -v`.

Backend có hai chế độ chạy, dùng hai tài khoản MySQL khác nhau:

| Chế độ | Tài khoản | Biến môi trường |
| --- | --- | --- |
| `migrate`: chạy Flyway một lần rồi thoát | `signa_migrator` (DDL, `GRANT OPTION`) | `SIGNA_DB_MIGRATOR_USER`, `SIGNA_DB_MIGRATOR_PASSWORD` |
| Ứng dụng chạy lâu dài, Flyway tắt | `signa_app` (quyền theo bảng, `audit_logs` chỉ `SELECT, INSERT`) | `SIGNA_DB_APP_USER`, `SIGNA_DB_APP_PASSWORD` |

Ứng dụng chạy lâu dài **không** đọc mật khẩu migrator. Nếu backend bị chiếm, kẻ tấn công vẫn không sửa hay xoá được `audit_logs` (FR-23).

### Chạy backend trong Compose

Dành cho web quản trị cần API thật mà không cài JDK. Image build từ [`Dockerfile`](Dockerfile) (JDK build, JRE chạy bằng user `signa` uid 10001):

```sh
docker compose -f infra/compose.yaml --profile app up -d --build   # hạ tầng, migrate, backend
docker compose -f infra/compose.yaml --profile migrate up migrate  # chỉ chạy migration
```

`migrate` chạy profile `migrate` với `SIGNA_DB_MIGRATOR_*` rồi thoát; `backend` chỉ nhận `SIGNA_DB_APP_*` và chỉ khởi động khi `migrate` kết thúc thành công. Backend nghe ở `http://127.0.0.1:8080`; web dev trỏ proxy Vite vào đó (`SIGNA_TARGET=http://localhost:8080`). Đặt `SIGNA_BOOTSTRAP_ADMIN_EMAIL`, `SIGNA_BOOTSTRAP_ADMIN_PASSWORD` trong `infra/.env` để có tài khoản đăng nhập web quản trị.

### Chạy bằng Gradle

```sh
set -a; . ../infra/.env; set +a                                     # trong backend/
./gradlew bootRun --args='--spring.profiles.active=migrate'         # migrate rồi thoát
env -u SIGNA_DB_MIGRATOR_PASSWORD \
  ./gradlew bootRun --args='--spring.profiles.active=local'         # chạy ứng dụng
```

Profile `local` ghi log dạng chữ và bật Swagger UI ở `/api/v1/swagger-ui`. Mặc định log là JSON (`logstash`). OpenAPI ở `/api/v1/openapi.json`, health ở `/actuator/health`.

## Biến môi trường

| Biến | Mặc định | Ý nghĩa |
| --- | --- | --- |
| `SIGNA_DB_URL` | `jdbc:mysql://localhost:3306/signa?connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true` | JDBC URL |
| `SIGNA_DB_APP_USER` | `signa_app` | Tài khoản ứng dụng |
| `SIGNA_DB_APP_PASSWORD` | (trống) | Mật khẩu tài khoản ứng dụng |
| `SIGNA_DB_MIGRATOR_USER` | (bắt buộc ở `migrate`) | Chỉ profile `migrate` đọc |
| `SIGNA_DB_MIGRATOR_PASSWORD` | (bắt buộc ở `migrate`) | Chỉ profile `migrate` đọc |
| `SIGNA_MIGRATE` | `false` | Bật Flyway ở chế độ thường; không dùng khi chạy thật |
| `SIGNA_REDIS_HOST`, `SIGNA_REDIS_PORT` | `localhost`, `6379` | Redis |
| `SIGNA_MINIO_ENDPOINT` | `http://localhost:9000` | MinIO, backend gọi qua mạng nội bộ |
| `SIGNA_MINIO_PUBLIC_ENDPOINT` | `http://localhost:9000` | Gốc của URL ký trả cho client |
| `SIGNA_MINIO_ACCESS_KEY`, `SIGNA_MINIO_SECRET_KEY` | (trống, bắt buộc) | Thiếu thì ứng dụng không khởi động |
| `SIGNA_RECOGNITION_URL` | `http://localhost:8000` | Dịch vụ nhận diện FastAPI |
| `SIGNA_CLUSTERING_WORKER_ENABLED` | `false` (Gradle), `true` (Compose) | Bật worker gom cụm trên một bản sao backend trong mỗi môi trường theo ADR-0007 |
| `SIGNA_LABELS_PATH` | `../ai/data/labels.yaml` | Danh mục nhãn cho `import-labels` (lát 2) |
| `SIGNA_CATALOG_IMPORT_ON_STARTUP` | `false` (Gradle), `true` (Compose) | Bật nạp danh mục lúc khởi động theo phiên bản nhãn |
| `SIGNA_PORT` | `8080` | Cổng HTTP |
| `SIGNA_TRUSTED_PROXIES` | dải IP riêng | Biểu thức chính quy các proxy được tin `X-Forwarded-For`, ví dụ `172\.18\.0\.5` |
| `SIGNA_BOOTSTRAP_ADMIN_EMAIL`, `SIGNA_BOOTSTRAP_ADMIN_PASSWORD` | (trống) | Quản trị viên đầu tiên, xem dưới |
| `SIGNA_OTP_SECRET` | (bắt buộc khi chạy web ngoài profile `test`) | Bí mật HMAC của OTP, tạo bằng `openssl rand -hex 32`; các bản sao backend dùng cùng giá trị |
| `SIGNA_SMTP_HOST`, `SIGNA_SMTP_PORT` | `localhost` (Gradle) hoặc `mailpit` (Compose), `1025` | Máy chủ SMTP |
| `SIGNA_SMTP_FROM` | `no-reply@signa.local` | Địa chỉ gửi OTP |

## Danh mục nhãn và dữ liệu mẫu

Khi bật `SIGNA_CATALOG_IMPORT_ON_STARTUP=true`, backend đọc `ai/data/labels.yaml`
và so với phiên bản nhãn lớn nhất trong CSDL.

- CSDL chưa có phiên bản nhãn hoặc tệp mới hơn thì đồng bộ đầy đủ.
- Cùng phiên bản thì chỉ thêm nhóm và loại còn thiếu, giữ chỉnh sửa của quản trị viên.
- Tệp cũ hơn thì bỏ qua và ghi cảnh báo.

Backend khoá hàng `catalog_generation` trước khi đọc phiên bản và giữ khoá
đến khi giao dịch nạp kết thúc. Khi chạy nhiều bản sao, chỉ bật cờ trên một
bản sao.

Tham số `--import-labels` luôn đồng bộ từ YAML, kể cả cùng phiên bản, và có
thể cập nhật nhóm và tốc độ trên loại biển. Lệnh giữ mô tả, ảnh minh hoạ,
trạng thái dùng và tên quản trị viên đã sửa. Tệp bị lỗi, chẳng hạn trùng
`id`, trùng `code` hoặc nhóm chưa khai báo, làm backend dừng và báo số dòng.

```sh
./gradlew bootRun --args='--spring.profiles.active=local --import-labels'
```

Profile `demo` thêm 30 biển mẫu quanh Hà Nội (`db/demo/R__demo_data.sql`) khi
migrate, cùng các loại biển chúng dùng. Backend có bật nạp lúc khởi động sẽ
thêm các loại còn thiếu từ YAML cùng phiên bản.

```sh
./gradlew bootRun --args='--spring.profiles.active=migrate,demo'
```

Có thể chạy lại profile `migrate` trên cùng volume sau khi nạp demo. Flyway
bỏ qua repeatable migration đã áp dụng nhưng vắng ở locations hiện hành
(`repeatable:missing`). Hiện chỉ `R__demo_data.sql` dùng locations riêng.
Các migration có số phiên bản vẫn được kiểm tra như trước.

## Lưu ảnh

Khi khởi động, backend tạo các bucket còn thiếu: `sign-crops`, `report-photos` (riêng tư, đọc qua URL ký) và `sign-icons` (đọc công khai). URL ký dùng `SIGNA_MINIO_PUBLIC_ENDPOINT` và được ký offline. Chữ ký gắn với host, nên proxy đứng trước MinIO phải giữ nguyên header `Host` của URL đó. Ảnh gửi chỉ để nhận diện không bao giờ được ghi vào MinIO (NFR-09).

## Tài khoản và phân quyền

API ở [`docs/07 §3`](../docs/07-api.md#3-tài-khoản). Token phiên là 32 byte ngẫu nhiên (base64url), gửi bằng `Authorization: Bearer`. Redis chỉ giữ SHA-256 của token: `session:{hash}` (hash `userId`, `credentialVersion`, `client`, `createdAt`; TTL 30 ngày với `MOBILE`, 8 giờ với `WEB`, gia hạn mỗi lần dùng) và `user-sessions:{userId}`. Mỗi request có phiên đọc trạng thái, vai trò và phiên bản xác thực hiện hành từ MySQL. Khi chuyển sang V100, các phiên Redis cũ cần đăng nhập lại. [CSDL §10](../docs/06-co-so-du-lieu.md#10-redis).

Quyền theo vai trò nạp từ `role_permissions` khi khởi động. Endpoint kiểm tra bằng `@PreAuthorize("hasAuthority('<mã quyền>')")` và lấy người dùng bằng `@AuthenticationPrincipal AuthenticatedUser`. Endpoint mới phải có dòng trong `PermissionMatrixTest` (UT-B11). Lỗi `401`, `403` của Spring Security và của `@PreAuthorize` cùng định dạng lỗi chung.

**Quên mật khẩu.** `POST /api/v1/auth/password/forgot` nhận email, trả `202` rỗng và gửi mã cho tài khoản hoạt động qua SMTP bất đồng bộ. Lần thứ 4 trong 15 phút theo cùng email và IP trả `429`. Khi chạy local, xem thư ở hộp thư Mailpit tại `http://127.0.0.1:8025`. Mã hợp lệ 10 phút hoặc đến lần nhập sai thứ 5. `POST /api/v1/auth/password/reset` đặt mật khẩu mới bằng mã và thu hồi mọi phiên.

Đặt `SIGNA_OTP_SECRET` trước khi chạy backend web. Thay bí mật làm mất hiệu lực các mã đã cấp. Profile kiểm thử tự tạo bí mật trong bộ nhớ nếu thiếu.

Giới hạn: sai mật khẩu quá 10 lần trong 15 phút theo `rl:login:{email}:{ip}` thì `429` kèm `Retry-After`; nhập sai mật khẩu hiện tại khi đổi mật khẩu giới hạn tương tự theo `rl:password:{userId}`. IP của client do `RemoteIpValve` của Tomcat xác định (`server.forward-headers-strategy: native`). Valve chỉ đọc `X-Forwarded-For` khi kết nối đến từ proxy khớp `SIGNA_TRUSTED_PROXIES`, mặc định là các dải IP riêng 10/8, 172.16/12, 192.168/16, 127/8 và `::1`. Client bên ngoài gửi header giả thì vẫn bị tính theo IP thật (`TrustedProxyForwardedForTest`, `UntrustedForwardedForTest`).

Hai điều kiện để giới hạn theo IP có hiệu lực:

- nginx (lát 10) phải **ghi đè** header bằng `proxy_set_header X-Forwarded-For $remote_addr;`, không dùng `$proxy_add_x_forwarded_for`. Nếu nối thêm, một máy trong mạng LAN (IP riêng, được tin như proxy) có thể chèn IP tuỳ ý vào trước chuỗi.
- Cổng backend không mở cho mạng có client dùng IP riêng. Compose chỉ bind `127.0.0.1`. Khi biết IP cố định của nginx, đặt `SIGNA_TRUSTED_PROXIES` đúng IP đó.

**Quản trị viên đầu tiên.** Khi khởi động, nếu chưa có tài khoản `ADMIN` và có đủ `SIGNA_BOOTSTRAP_ADMIN_EMAIL` cùng `SIGNA_BOOTSTRAP_ADMIN_PASSWORD`, backend tạo tài khoản `ADMIN` và ghi lịch sử `USER/ROLE_CHANGED` với `actor_type = SYSTEM`. Thiếu biến thì bỏ qua; mật khẩu sai chính sách thì không khởi động. Email đã thuộc tài khoản khác thì không nâng quyền tài khoản đó. Không có mật khẩu mặc định.
