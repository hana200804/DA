# Lát 3 · Nhận diện

> Kế hoạch thực thi theo [thiết kế triển khai](../thiet-ke-trien-khai.md) §5.3 hàng "Nhận diện", "Giới hạn tần suất" và §6 hàng "3 · Nhận diện". Làm song song với lát 1, 2, 5 theo quy tắc song song `docs/backend/quy-tac-song-song.md` của người điều phối. Đánh dấu `- [x]` khi xong từng bước.

**Mục tiêu:** backend làm proxy tới dịch vụ nhận diện Python ([API §7](../../07-api.md#7-dịch-vụ-nhận-diện-nội-bộ)): `GET /api/v1/recognition/models`, `POST /api/v1/recognition/detect` có giới hạn tần suất, thu nhỏ ảnh, quy đổi box về ảnh gốc (FR-02, FR-24).

**Công nghệ thêm:** `RestClient` trên `JdkClientHttpRequestFactory`, `ImageIO` cùng `com.twelvemonkeys.imageio:imageio-webp` 3.15.3, Lua script trên Redis, Micrometer. Test: `org.wiremock:wiremock-standalone` 3.13.2 (dòng 4.x mới có bản beta).

## Ràng buộc chung

- Hợp đồng với dịch vụ lấy từ mã thật `ai/service/app.py`, `ai/service/images.py`: multipart `image` cùng `model` tuỳ chọn; ảnh tối đa 10 000 000 byte và 25 triệu pixel; hàng chờ đầy hoặc hết hạn chờ slot trả `503` kèm `Retry-After: 1`; `/v1/models` trả `defaultModel` và `models[]` (`modelName`, `modelVersion`, `labelMapVersion`, `defaultConf`, `modelCard`).
- Ảnh chỉ nằm trong bộ nhớ (NFR-09): không ghi MinIO, không ghi đĩa, không log nội dung ảnh. `ImageIO` đọc ghi qua `MemoryCacheImageInputStream`, `MemoryCacheImageOutputStream` để không dùng file cache.
- Đợt song song chưa có Spring Security: endpoint đều công khai; người dùng đăng nhập lấy qua interface `CallerIdentity` (hiện luôn rỗng).
- Chỉ thêm dòng ở cuối `dependencies` kèm `// lát 3`. Không sửa `shared/`. `application.yml` chỉ thêm `spring.servlet.multipart.file-size-threshold` (người điều phối cho phép); cấu hình riêng nằm ở `RecognitionProperties` (`signa.recognition`) với giá trị mặc định trong mã, `signa.recognition.url` có sẵn từ lát 0.

## Điểm cần soi khi review

1. Dịch vụ trả `503`, chậm quá 5 giây hoặc không kết nối được → `503 RECOGNITION_UNAVAILABLE` kèm `Retry-After: 1`, không phải `500`.
2. Ảnh 2560×1920 → dịch vụ nhận JPEG 1280×960; box trả về nhân 2 theo từng trục; `image` là 2560×1920.
3. File text đặt tên `.jpg`, khai `image/jpeg` → `415`; định dạng xác định bằng magic bytes, không tin đuôi file hay `Content-Type`.
4. Ảnh khai kích thước khổng lồ (bom giải nén) → `413` trước khi giải mã, không làm hết bộ nhớ.
5. Lần thứ 31 trong một phút từ cùng IP khách → `429` kèm `Retry-After`; khoá Redis luôn có TTL (INCR và EXPIRE trong một Lua script).
6. Không có đường nào ghi ảnh ra MinIO hay đĩa; log không chứa byte ảnh.

## Phần 1 · Làm trong đợt song song

### Nhiệm vụ 1 · Xử lý ảnh

**Tạo:** `recognition/internal/ImageFormat.java`, `PreparedImage.java`, `ImageProcessor.java`, `Box.java`; test `ImageFormatTest`, `ImageProcessorTest`.

- [x] `ImageFormat.detect(byte[])`: JPEG `FF D8 FF`, PNG `89 50 4E 47 0D 0A 1A 0A`, WebP `RIFF····WEBP`; khác thì `415 UNSUPPORTED_MEDIA_TYPE`.
- [x] `ImageProcessor.prepare`: đọc kích thước từ header; quá 25 triệu pixel → `413`; dữ liệu hỏng → `415`. Cạnh dài ≤ 1280 px thì gửi nguyên byte; lớn hơn thì giải mã, thu về 1280 px, nền trắng cho ảnh có alpha, mã hoá JPEG chất lượng 0,9.
- [x] `Box.scale(sx, sy)` quy đổi box theo tỉ lệ từng trục giữa ảnh gốc và ảnh gửi.
- [x] Unit test: magic bytes của cả ba định dạng, text, dữ liệu ngắn; quy đổi box; ảnh lớn được thu nhỏ đúng kích thước; ảnh nhỏ gửi nguyên; PNG khai 6000×6000 bị chặn mà không giải mã.
- [x] Commit.

### Nhiệm vụ 2 · `RecognitionClient`

**Tạo:** `recognition/internal/RecognitionProperties.java`, `RecognitionClient.java`, `ModelList.java`, `ServiceDetection.java`; test `RecognitionClientTest` (`MockRestServiceServer`, không cần Docker).

- [x] `RestClient` gọi `signa.recognition.url`, timeout kết nối 1 giây, đọc 5 giây; truyền `X-Request-Id` lấy từ MDC.
- [x] `503`, lỗi `5xx` khác, timeout, lỗi kết nối, body hỏng → `ApiException(RECOGNITION_UNAVAILABLE)` kèm `Retry-After: 1`. `400`, `413`, `415` của dịch vụ → `VALIDATION_ERROR`, `PAYLOAD_TOO_LARGE`, `UNSUPPORTED_MEDIA_TYPE`.
- [x] `models()` cache trong bộ nhớ 60 giây theo `Clock`; lỗi không được cache.
- [x] Unit test: header `X-Request-Id`, trường multipart, ánh xạ từng mã lỗi, timeout giả, cache trúng và hết hạn.
- [x] Commit.

### Nhiệm vụ 3 · Giới hạn tần suất

**Tạo:** `recognition/internal/CallerIdentity.java`, `AnonymousCallerIdentity.java`, `RateLimit.java`, `RecognitionRateLimiter.java`; test `RateLimitTest`.

- [x] Khách 30 lần mỗi phút, khoá `rl:recognize:ip:<ip>`; người dùng 10 lần mỗi phút, khoá `rl:recognize:user:<id>`. IP lấy từ `request.getRemoteAddr()` (`forward-headers-strategy: framework` đã bật).
- [x] Cửa sổ cố định bằng Lua `INCR` + `EXPIRE` + `TTL`; vượt giới hạn → `429 RATE_LIMITED`, `Retry-After` là TTL còn lại.
- [x] Redis lỗi thì cho qua và ghi cảnh báo (fail-open): nhận diện công khai, dịch vụ đã tự giới hạn hàng chờ.
- [x] `CallerIdentity.userId()` trả `Optional<String>`; bản hiện tại luôn rỗng.
- [x] Unit test tính khoá và giới hạn theo người gọi.
- [x] Commit.

### Nhiệm vụ 4 · Endpoint, tra cứu biển, metric

**Tạo:** `recognition/internal/SignInfoLookup.java`, `NoSignInfoLookup.java`, `RecognitionService.java`, `DetectResult.java`; `recognition/web/RecognitionController.java`; test `RecognitionControllerTest` (`@WebMvcTest`).

- [x] `GET /api/v1/recognition/models` trả `defaultModel` và `models[]` (`modelName`, `modelVersion`, `labelMapVersion`, `modelCard`) đúng như dịch vụ.
- [x] `POST /api/v1/recognition/detect`: multipart `image`, `model` tuỳ chọn trong `yolov5`, `yolov8`, `detr`, sai thì `400`. Thứ tự: giới hạn tần suất, kích thước ≤ 10 000 000 byte (`413`), magic bytes (`415`), chuẩn bị ảnh, gọi dịch vụ, quy đổi box, bổ sung `name`, `description`, `iconUrl`.
- [x] `SignInfoLookup.lookup(codes)` tra theo lô; bản hiện tại trả rỗng nên ba trường là `null`.
- [x] Response theo [API §5](../../07-api.md#5-nhận-diện): `model { name, version, labelMapVersion }`, `image { width, height }`, `inferenceMs`, `detections[]`.
- [x] Timer `signa.recognition.duration`, tag `model` (`yolov5`, `yolov8`, `detr`, `default`) và `outcome` (`success`, `unavailable`, `rejected`).
- [x] `@WebMvcTest`: models; detect thành công đúng JSON; `model` sai; `415`; `413`; `429` có `Retry-After`; `503` có `Retry-After`; metric được ghi.
- [x] Commit.

### Nhiệm vụ 5 · Test tích hợp với WireMock

**Tạo:** `src/test/java/vn/signa/recognition/RecognitionIT.java`.

- [x] IT-B08: dịch vụ giả trả chậm 6 giây → `503 RECOGNITION_UNAVAILABLE` sau khoảng 5 giây.
- [x] IT-B09: sau khi nhận diện, không bucket MinIO nào có object mới.
- [x] IT-B13: ảnh 2560×1920 → dịch vụ giả nhận ảnh 1280×960, box trả về đúng toạ độ ảnh gốc, `image` là 2560×1920.
- [x] `429` ở lần thứ 31 từ cùng IP; `415` với file text đặt tên `.jpg`.
- [x] Chỉ compile trên máy, chạy trên CI. Commit.

## Phần 2 · Sau khi merge lát 1 và lát 2

- [x] Merge `backend/2-catalog` (gồm lát 1 và lát 2); xung đột chỉ ở `build.gradle.kts`, giữ cả hai bên.
- [x] Nối `CallerIdentity` với principal của lát 1 (người dùng đăng nhập dùng giới hạn 10 lần mỗi phút): `PrincipalCallerIdentity`, xoá `AnonymousCallerIdentity`. Thêm dòng cho hai endpoint công khai vào `PermissionMatrixTest`.
- [x] Nối `SignInfoLookup` với `catalog.api.SignCatalog` của lát 2: `CatalogSignInfoLookup`, xoá `NoSignInfoLookup`.
- [ ] `POST /admin/recognition/compare` (FR-37, quyền `recognition:compare`) làm ở lát 9.

## Tiến độ

Ghi ở đây trạng thái cuối và việc còn dở khi dừng giữa chừng.

**2026-11-23.** Phần 1 (NV1–NV5) đã commit trên `backend/3-recognition`, chưa push.

- Trên máy: `./gradlew spotlessCheck test compileTestJava` thành công, 62/62 unit test đạt, trong đó 42 test mới của lát 3 (`ImageFormatTest` 4, `ImageProcessorTest` 8, `RecognitionClientTest` 12, `RateLimitTest` 7, `RecognitionControllerTest` 10, `UploadConfigTest` 1). `markdownlint` sạch.
- Đã thử tay (test tạm, không commit) `RecognitionClient` thật với WireMock: dịch vụ chậm 6 giây thì lỗi sau 5011 ms; địa chỉ không phản hồi thì lỗi sau 1002 ms; ảnh 2560×1920 tới dịch vụ là JPEG 1280×960.
- Chỉ verify được trên CI (máy không có Docker): `RecognitionIT` gồm IT-B08, IT-B09, IT-B13, `429` ở lần thứ 31, `415`, truyền `X-Request-Id` khi gọi `/v1/models`; Lua script giới hạn tần suất chạy trên Redis thật.
- Quyết định:
  - Giới hạn 10 000 000 byte và 25 triệu pixel, bằng đúng dịch vụ.
  - `model` chỉ nhận `yolov5`, `yolov8`, `detr`, không nhận `modelVersion` dù dịch vụ có nhận.
  - Ảnh có cạnh dài ≤ 1280 px được gửi nguyên byte.
  - Lỗi `5xx` nào của dịch vụ cũng thành `503`; `400`, `413`, `415` giữ nguyên ý nghĩa.
  - Box trả số thực, không làm tròn.
  - Redis lỗi thì cho qua (fail-open).
  - Cấu hình riêng nằm ở `RecognitionProperties` với giá trị mặc định trong mã. `application.yml` chỉ thêm `file-size-threshold` theo cho phép của người điều phối.
- Nối ở phần 2 (đã xong 2026-11-24): `AnonymousCallerIdentity` (luôn khách) thay bằng principal của lát 1; `NoSignInfoLookup` (`name`, `description`, `iconUrl` luôn `null`) thay bằng `catalog.api.SignCatalog` của lát 2.
- Đã xử lý theo cho phép của người điều phối: thêm `spring.servlet.multipart.file-size-threshold: 11MB` vào `application.yml`. Ngưỡng không nhỏ hơn `max-file-size` và `max-request-size`, nên Tomcat giữ ảnh nhận diện trong bộ nhớ, không ghi file tạm (NFR-09). `UploadConfigTest` chứng minh cấu hình này.
- Rủi ro người điều phối đã chấp nhận:
  - Redis lỗi thì cho qua (fail-open).
  - Ảnh thu nhỏ bị bỏ thẻ EXIF Orientation. Ứng dụng chuẩn hoá hướng ảnh trước khi tải lên theo docs/07 §5, nên box và `image` cùng một hệ toạ độ.
  - JPEG CMYK lớn hơn 1280 px có thể trả `415`; khi cần thì thêm `imageio-jpeg` của TwelveMonkeys.
- Rủi ro `X-Forwarded-For` giả mạo: lát 1 đã đổi sang `forward-headers-strategy: native` với `server.tomcat.remoteip.internal-proxies`, nên chỉ proxy nội bộ đặt được IP khách (xem mục 2026-11-24).

**2026-11-24.** Phần 2 đã commit trên `backend/3-recognition`, chưa push.

- Merge `backend/2-catalog` vào lát 3 (`ed9d1ae`). Xung đột chỉ ở `build.gradle.kts`, giữ cả dòng lát 1 và lát 3. Sau merge, `RecognitionControllerTest` dùng `@WithApiSecurity` của lát 1, `PermissionMatrixTest` có `@MockitoBean RecognitionService`.
- `.gitignore` của `backend/` bỏ qua `.project`, `.classpath`, `.settings/`, `bin/` do IDE sinh ra.
- `PrincipalCallerIdentity` đọc `AuthenticatedUser` trong `SecurityContext`: đã đăng nhập thì khoá `rl:recognize:user:<userId>`, 10 lần mỗi phút; khách và xác thực ẩn danh thì khoá `rl:recognize:ip:<ip>`, 30 lần mỗi phút. `RecognitionControllerTest` chạy qua chuỗi Security thật với token giả để chứng minh.
- `CatalogSignInfoLookup` lấy `name`, `description`, `iconUrl` (`/icons/<iconKey>`) qua `SignCatalog.findType`. Mỗi mã khác nhau tra một lần. Mã không có trong danh mục thì ba trường là `null`. Danh mục lỗi CSDL thì kết quả nhận diện vẫn trả, chỉ thiếu ba trường.
- `PermissionMatrixTest`: hai dòng `reserved` của lát 3 đổi sang `Endpoint.of` và `Endpoint.withFile`, công khai cho cả 4 vai trò. `SecurityConfig` của lát 1 đã mở `GET /recognition/**` và `POST /recognition/detect`.
- IP khách: lát 1 đổi `forward-headers-strategy` sang `native` với `server.tomcat.remoteip.internal-proxies` (mặc định các dải IP riêng). `request.getRemoteAddr()` chỉ lấy `X-Forwarded-For` khi request đến từ proxy tin cậy, nên rủi ro giả IP để vượt giới hạn đã được lát 1 xử lý. nginx vẫn phải đặt `X-Forwarded-For $remote_addr`.
- Trên máy: `./gradlew spotlessApply test compileTestJava` thành công, 286/286 unit test đạt. Gói `recognition` có 51 test, mới thêm `PrincipalCallerIdentityTest` 4 và `CatalogSignInfoLookupTest` 5; ngoài ra có 8 ca nhận diện trong `PermissionMatrixTest`. `markdownlint` sạch.
- Chỉ verify được trên CI: `RecognitionIT` thêm ca nạp `ai/data/labels.yaml` rồi kiểm `P.102` có tên "Cấm đi ngược chiều", mã lạ có `name = null`.
- Còn lại: `POST /admin/recognition/compare` (FR-37) ở lát 9. Nếu danh mục lớn hoặc ảnh có nhiều biển, có thể thêm hàm tra theo lô vào `SignCatalog` (lát 2 sở hữu); hiện một ảnh vài biển nên tra từng mã là đủ.
