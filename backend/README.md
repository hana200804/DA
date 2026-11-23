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

Từ gốc repo, dựng MySQL, Redis, MinIO:

```sh
cp infra/.env.example infra/.env   # rồi đổi mọi mật khẩu
docker compose -f infra/compose.yaml up -d
```

Cổng chỉ mở trên `127.0.0.1`: MySQL 3306, Redis 6379, MinIO 9000 (console 9001). Script `01-users.sh` chỉ chạy khi volume MySQL được tạo lần đầu. Đổi mật khẩu sau đó thì xoá volume: `docker compose -f infra/compose.yaml down -v`.

Backend có hai chế độ chạy, dùng hai tài khoản MySQL khác nhau:

| Chế độ | Tài khoản | Biến môi trường |
| --- | --- | --- |
| `migrate`: chạy Flyway một lần rồi thoát | `signa_migrator` (DDL, `GRANT OPTION`) | `SIGNA_DB_MIGRATOR_USER`, `SIGNA_DB_MIGRATOR_PASSWORD` |
| Ứng dụng chạy lâu dài, Flyway tắt | `signa_app` (quyền theo bảng, `audit_logs` chỉ `SELECT, INSERT`) | `SIGNA_DB_APP_USER`, `SIGNA_DB_APP_PASSWORD` |

Ứng dụng chạy lâu dài **không** đọc mật khẩu migrator. Nếu backend bị chiếm, kẻ tấn công vẫn không sửa hay xoá được `audit_logs` (FR-23). Lát 1 sẽ thêm service `migrate` chạy một lần vào Compose. Hiện tại chạy bằng Gradle:

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
| `SIGNA_LABELS_PATH` | `../ai/data/labels.yaml` | Danh mục nhãn cho `import-labels` (lát 2) |
| `SIGNA_PORT` | `8080` | Cổng HTTP |

## Lưu ảnh

Khi khởi động, backend tạo các bucket còn thiếu: `sign-crops`, `report-photos` (riêng tư, đọc qua URL ký) và `sign-icons` (đọc công khai). URL ký dùng `SIGNA_MINIO_PUBLIC_ENDPOINT` và được ký offline. Chữ ký gắn với host, nên proxy đứng trước MinIO phải giữ nguyên header `Host` của URL đó. Ảnh gửi chỉ để nhận diện không bao giờ được ghi vào MinIO (NFR-09).
