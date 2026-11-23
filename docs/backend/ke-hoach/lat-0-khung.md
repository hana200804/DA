# Lát 0 · Khung backend

> Kế hoạch thực thi theo [thiết kế triển khai](../thiet-ke-trien-khai.md). Đánh dấu `- [x]` khi xong từng bước để người khác (hoặc Codex) làm tiếp được.

**Mục tiêu:** một ứng dụng Spring Boot chạy được, có lược đồ CSDL đầy đủ, định dạng lỗi chung, ranh giới module được test, Compose cho hạ tầng và CI.

**Công nghệ:** Java 21, Gradle 9.9.2 (wrapper, Kotlin DSL), Spring Boot 4.2.0, springdoc 3.2.1, Flyway, MySQL 8.4, Redis 7.4, MinIO, Testcontainers 2, ArchUnit 1.5.

## Ràng buộc chung

- Mã nằm trong `backend/`, gói gốc `vn.signa`, hạ tầng trong `infra/`.
- Không có Docker trên máy: `./gradlew test` (unit, không container) phải chạy được trên máy; `./gradlew integrationTest` (Testcontainers, tên lớp `*IT`) chạy trên CI.
- Lược đồ đúng [CSDL](../../06-co-so-du-lieu.md); tài khoản `signa_app` chỉ có `SELECT, INSERT` trên `audit_logs`.
- Lỗi theo [API §2](../../07-api.md#2-định-dạng-lỗi); mọi response có `X-Request-Id`.
- Chưa có Spring Security ở lát này (lát 1).

## Điểm cần soi khi review

1. Request không có `X-Request-Id` → server tự sinh UUID và trả lại header; request có giá trị dài quá 64 ký tự hoặc ký tự lạ → bỏ và tự sinh.
2. Lỗi không lường trước → `500 INTERNAL_ERROR` không lộ stack trace hay thông điệp nội bộ.
3. Body JSON hỏng hoặc sai kiểu → `400 VALIDATION_ERROR`, không phải `500`.
4. Tài khoản `signa_app` `UPDATE`/`DELETE` `audit_logs` → bị MySQL từ chối.
5. Một module `internal` dùng lớp `internal` của module khác → test kiến trúc fail.

## Các nhiệm vụ

### Nhiệm vụ 1 · Dự án Gradle và ứng dụng rỗng

**Tạo:** `backend/settings.gradle.kts`, `backend/build.gradle.kts`, `backend/gradle/wrapper/*`, `backend/gradlew*`, `backend/src/main/java/vn/signa/SignaApplication.java`, `backend/src/main/resources/application.yml`, `backend/.gitignore`.

- [x] Wrapper Gradle 9.9.2; plugin `org.springframework.boot` 4.2.0, `io.spring.dependency-management`, `jacoco`, `com.diffplug.spotless` (google-java-format).
- [x] Phụ thuộc: `spring-boot-starter-webmvc`, `-validation`, `-actuator`, `-jdbc`, `-flyway`, `-data-redis`, `flyway-mysql`, `mysql-connector-j`, `springdoc-openapi-starter-webmvc-ui` 3.2.1, `io.minio:minio`. Test: `spring-boot-starter-test`, `-webmvc-test`, `archunit-junit5`, `testcontainers-junit-jupiter`, `testcontainers-mysql`, `spring-boot-testcontainers`.
- [x] Task `test` loại `**/*IT.class`; task `integrationTest` chỉ chạy `**/*IT.class`; `check` phụ thuộc cả hai.
- [x] `./gradlew compileJava` thành công.

### Nhiệm vụ 2 · Lỗi chung và `X-Request-Id`

**Tạo:** `shared/web/ErrorResponse.java` (`record ErrorResponse(String code, String message, List<FieldIssue> details, String requestId)`, `record FieldIssue(String field, String issue)`), `shared/error/ErrorCode.java` (enum theo bảng API §2, mỗi mã giữ `HttpStatus`), `shared/error/ApiException.java` (`ApiException(ErrorCode, String message)` cùng `details`, `retryAfterSeconds`), `shared/web/GlobalExceptionHandler.java`, `shared/web/RequestIdFilter.java` (`HEADER = "X-Request-Id"`, MDC key `requestId`).

- [x] Test `GlobalExceptionHandlerTest` (`@WebMvcTest` với controller giả trong test): `ApiException(NOT_FOUND)` → 404 đúng body; `@Valid` sai → 400 có `details[].field`; JSON hỏng → 400; `RuntimeException` → 500 với message chung; `RATE_LIMITED` có `Retry-After`; mọi lỗi có `requestId` bằng header response.
- [x] Test `RequestIdFilterTest`: không header → sinh UUID; header hợp lệ `[A-Za-z0-9._-]{1,64}` → giữ; header không hợp lệ → thay.
- [x] Cài đặt, chạy `./gradlew test`, commit.

### Nhiệm vụ 3 · Module và test kiến trúc

**Tạo:** `package-info.java` cho `identity`, `catalog`, `sign`, `recognition`, `audit` và các gói con `api`, `internal`, `web`; `src/test/java/vn/signa/architecture/ModuleBoundaryTest.java`, `TableOwnershipTest.java`.

- [x] `ModuleBoundaryTest` (ArchUnit): lớp của module A chỉ được dùng `vn.signa.<B>.api..` của module B; `shared` không phụ thuộc module nghiệp vụ; không chu trình giữa các module.
- [x] `TableOwnershipTest`: đọc mọi file `.java` dưới `src/main/java/vn/signa/<module>/`, tìm tên bảng (`\b(users|roles|...)\b` trong chuỗi SQL `FROM|JOIN|INTO|UPDATE`), fail nếu bảng không thuộc module theo bảng sở hữu (Kiến trúc §5). `audit_logs` chỉ thuộc `audit`.
- [x] Mỗi test có một ca "tự kiểm" dùng lớp mẫu vi phạm trong `src/test` để chứng minh luật bắt được lỗi.
- [x] Chạy, commit.

### Nhiệm vụ 4 · Lược đồ Flyway và tài khoản CSDL

**Tạo:** `backend/src/main/resources/db/migration/V1__schema.sql`, `V2__roles_permissions.sql`, `V3__app_grants.sql` (dùng placeholder `${app_user}`), `infra/mysql/init/01-users.sh`, `src/test/java/vn/signa/support/Containers.java`, `IntegrationTest.java`, `src/test/java/vn/signa/db/SchemaIT.java`.

- [x] `V1`: mọi bảng ở CSDL §4–§8 với đúng kiểu, ràng buộc, chỉ mục, `SPATIAL INDEX` trên `POINT SRID 4326`, cột sinh `pending_flag`.
- [x] `V2`: 3 vai trò, 11 quyền, ma trận ở CSDL §4.
- [x] `V3`: cấp quyền tối thiểu theo từng bảng cho `${app_user}`: `roles`, `permissions`, `role_permissions` chỉ `SELECT`; bảng nghiệp vụ `SELECT, INSERT, UPDATE`, thêm `DELETE` chỉ ở `sign_code_weights`; riêng `audit_logs` chỉ `SELECT, INSERT`.
- [x] `01-users.sh`: tạo `signa_migrator` (`ALL ON signa.* WITH GRANT OPTION`) và `signa_app` (không quyền mức CSDL) từ biến `SIGNA_MIGRATOR_PASSWORD`, `SIGNA_APP_PASSWORD`.
- [x] `support/Containers` cùng lớp cha `support/IntegrationTest`: MySQL 8.4 chép `01-users.sh` vào `/docker-entrypoint-initdb.d/`, Redis 7.4, MinIO; dùng chung cho mọi `*IT`.
- [x] `SchemaIT`: migration chạy xong; có đủ 11 quyền; `signa_app` `INSERT` được `audit_logs` nhưng `UPDATE`, `DELETE` bị từ chối; `signa_app` đọc ghi được `signs`. Chỉ compile trên máy, chạy trên CI.
- [x] Commit.

### Nhiệm vụ 5 · Cấu hình, lưu trữ ảnh, Compose

**Tạo:** `shared/storage/ObjectStorage.java` (interface: `put(Bucket, String key, byte[] data, String contentType)`, `delete(Bucket, String key)`, `presignedGet(Bucket, String key, Duration ttl)`, `exists(Bucket, String key)`), `shared/storage/Bucket.java` (enum `SIGN_CROPS("sign-crops")`, `REPORT_PHOTOS("report-photos")`, `SIGN_ICONS("sign-icons")`), `shared/storage/MinioObjectStorage.java` (tạo bucket còn thiếu khi khởi động qua `MinioBucketInitializer`; `sign-icons` đọc công khai; URL ký theo `signa.minio.public-endpoint`), `shared/storage/ObjectStorageException.java`, `shared/config/MigrateAndExit.java`, `application-migrate.yml`, `shared/config/SignaProperties.java`, `shared/time/ClockConfig.java`, `application.yml`, `infra/compose.yaml`, `infra/.env.example`, `src/test/java/vn/signa/ApplicationIT.java`, `shared/storage/MinioObjectStorageIT.java`, `shared/storage/MinioObjectStorageTest.java`, `shared/config/ApplicationConfigTest.java`.

- [x] Cấu hình qua biến môi trường: `SIGNA_DB_URL`, `SIGNA_DB_APP_USER/PASSWORD`, `SIGNA_DB_MIGRATOR_USER/PASSWORD` (chỉ profile `migrate`), `SIGNA_REDIS_HOST/PORT`, `SIGNA_MINIO_ENDPOINT/ACCESS_KEY/SECRET_KEY`, `SIGNA_RECOGNITION_URL`, `SIGNA_LABELS_PATH`. Log có cấu trúc `logstash` ở profile mặc định, văn bản thường ở `local`.
- [x] Tách migrator khỏi backend chạy lâu dài (rà soát bảo mật): mặc định `spring.flyway.enabled: ${SIGNA_MIGRATE:false}`, không có user/password migrator; profile `migrate` bật Flyway, `web-application-type: none`, `MigrateAndExit` thoát sau khi migrate; test IT bật Flyway qua `@DynamicPropertySource`; `ApplicationConfigTest` chứng minh mặc định tắt.
- [x] `ApplicationIT`: ứng dụng khởi động trên container; `/actuator/health` trả `UP`; `/api/v1/openapi.json` trả 200; ba bucket tồn tại sau khởi động. Chỉ compile trên máy, chạy trên CI.
- [x] `MinioObjectStorageIT`: `put` rồi `presignedGet` đọc được; không chữ ký thì `sign-crops`, `report-photos` trả 403; `sign-icons` đọc công khai. Chỉ compile trên máy, chạy trên CI. Unit test `MinioObjectStorageTest` chứng minh ký URL theo `public-endpoint` không gọi mạng.
- [x] `compose.yaml`: `mysql` (8.4, mount `infra/mysql/init`), `redis` (7.4), `minio`, healthcheck cho cả ba; cổng chỉ bind `127.0.0.1`. MinIO dùng `pgsty/minio` vì ảnh `minio/minio` không còn công khai. Chưa chạy `docker compose config` (máy không có Docker).
- [x] Commit.

### Nhiệm vụ 6 · CI và README

**Tạo:** `.github/workflows/backend.yml`, `backend/README.md`.

- [x] Workflow chạy khi PR đụng `backend/**`, `infra/**`, workflow này: JDK 21 Temurin, `./gradlew spotlessCheck test integrationTest jacocoTestReport`, tải báo cáo test khi fail; job `gitleaks` (8.30.1, kiểm SHA-256, chỉ quét commit của PR hoặc lần push vì lịch sử cũ có một fixture MD5 trong `web-admin` bị báo nhầm). `actionlint` sạch.
- [x] README: yêu cầu, cách chạy `test`/`integrationTest`, `docker compose -f infra/compose.yaml up -d`, biến môi trường, phiên bản đã chọn.
- [ ] `./gradlew spotlessApply test` sạch; markdownlint sạch; commit; push; CI xanh. Đã xong tới commit; chưa push vì chưa được phép thao tác GitHub.

## Tiến độ

Ghi ở đây trạng thái cuối và việc còn dở khi dừng giữa chừng.

**2026-11-23.** NV1–NV6 đã commit trên `backend/0-scaffold`, chưa push.

- Trên máy: `./gradlew spotlessCheck test compileTestJava` thành công, 20/20 unit test đạt (kiến trúc 5, lỗi chung và `X-Request-Id` 9, cấu hình 2, lưu ảnh 4). `markdownlint`, `actionlint` sạch; gitleaks không thấy bí mật trong các commit của nhánh.
- Chỉ verify được trên CI (máy không có Docker): `SchemaIT`, `ApplicationIT`, `MinioObjectStorageIT`, healthcheck và `docker compose config` của `infra/compose.yaml`, chế độ `migrate` chạy thật với MySQL.
- Việc còn lại: push, mở PR, sửa nếu CI đỏ, đánh dấu bước cuối của nhiệm vụ 6.
- Rủi ro: MinIO dùng ảnh cộng đồng `pgsty/minio` vì `minio/minio` không còn công khai; nên chốt với nhóm. Proxy trước MinIO phải giữ header `Host` để URL ký hợp lệ. Plan gốc ghi `V3` cấp `DELETE` mọi bảng nghiệp vụ, mã thực tế chỉ cấp `DELETE` trên `sign_code_weights`; lát sau cần `DELETE` ở đâu thì thêm migration.
