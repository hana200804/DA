# Lát 4 · Bản đồ

> Kế hoạch thực thi theo [thiết kế triển khai](../thiet-ke-trien-khai.md) §5.3 hàng "Bản đồ" và §6 hàng "4 · Bản đồ". Đánh dấu `- [x]` khi xong từng bước. Lát này chạy song song với lát 2, 3, 5 theo quy tắc song song (`docs/backend/quy-tac-song-song.md` trên nhánh điều phối).

**Mục tiêu:** phần đọc công khai của bản đồ: biển trong vùng (hoặc điểm gộp theo ô lưới) và chi tiết một biển.

**Yêu cầu:** FR-05, FR-06, FR-07, NFR-03. **Ca nghiệm thu:** IT-B06, IT-B07; `404` với biển chưa công bố.

## Ràng buộc chung

- Mã nằm trong module `sign`, gói `vn.signa.sign.internal.map` và `vn.signa.sign.web`. Không đụng `vn.signa.sign.clustering` (lát 5).
- Chỉ đọc bảng `signs`. Tên, ý nghĩa, ảnh minh hoạ do client lấy từ danh mục (lát 2).
- `JdbcClient` cùng SQL viết tay; toạ độ theo `docs/06 §9`, `axis-order=long-lat`.
- Không thêm migration, không sửa `build.gradle.kts`, `application.yml`, `shared/`.
- Endpoint công khai, không cần Spring Security.

## Điểm cần soi khi review

1. `bbox` thiếu, sai số phần tử, không phải số, ngoài WGS84, `min ≥ max`, hoặc vắt qua kinh tuyến 180° (`minLng > maxLng`) → `400 VALIDATION_ERROR` có `details`.
2. Biển `PENDING`, `REJECTED`, `REMOVED` không lộ ra ở cả danh sách lẫn chi tiết.
3. Đúng thứ tự trục: điểm lat 21.0285, lng 105.8048 trả về đúng như vậy.
4. Được 501 dòng thì chuyển sang gom ô trên toàn vùng; tổng `count` bằng số biển khớp, không bị giới hạn 501.
5. `coverCropUrl` là URL ký 10 phút của bucket riêng tư `sign-crops`, `null` khi biển chưa có ảnh.

## Phần 1 · Làm trong đợt song song

### Nhiệm vụ 1 · Kế hoạch

- [x] Viết file này, commit.

### Nhiệm vụ 2 · Đọc tham số bản đồ

**Tạo:** `sign/internal/map/BoundingBox.java`, `MapQuery.java`, `MapQueryParser.java`, `GridCell.java`; test `MapQueryParserTest`, `GridCellTest`, `BoundingBoxTest`.

- [x] `bbox=minLng,minLat,maxLng,maxLat`: đủ 4 số hữu hạn, `lng ∈ [-180, 180]`, `lat ∈ [-90, 90]`, `minLng < maxLng`, `minLat < maxLat`; `minLng > maxLng` báo riêng là vắt qua kinh tuyến 180°.
- [x] `zoom` số nguyên 0–22, mặc định 14 khi vắng.
- [x] `categories`, `signTypeCodes`: danh sách phân cách dấu phẩy, bỏ khoảng trắng và mục rỗng, bỏ trùng, tối đa 50 mục, mỗi mã không quá độ dài cột (20 và 30 ký tự).
- [x] Gom mọi lỗi vào một `ApiException(VALIDATION_ERROR)` với `details[].field` là tên tham số.
- [x] `GridCell.sizeDeg(zoom) = 360 / 2^zoom / 8`, nới ra khi `bbox` quá rộng so với `zoom` để có tối đa 128 × 128 ô (rà soát bảo mật: giới hạn kích thước kết quả).
- [x] WKT của `bbox` viết số dạng thập phân thường (không `1.0E-4`), cạnh đông–tây chia nhỏ để không cạnh nào vượt 90° kinh độ.

### Nhiệm vụ 3 · Truy vấn và dịch vụ

**Tạo:** `sign/internal/map/MapSignRepository.java`, `MapService.java`, các record kết quả.

- [x] Truy vấn biển trong vùng theo `docs/06 §9`: `MBRContains(ST_GeomFromText(:polygon, 4326, 'axis-order=long-lat'), location)`, `status IN ('APPROVED','UNDER_REVIEW')`, lọc nhóm và mã khi có, `LIMIT 501`, đọc toạ độ bằng `ST_Latitude`, `ST_Longitude`.
- [x] Tối đa 500 dòng → `{ mode: "SIGNS", items: [{ id, signTypeCode, categoryCode, location, headingDeg, status, confidence }] }`.
- [x] Được 501 dòng → truy vấn gom `GROUP BY FLOOR(lng / cell), FLOOR(lat / cell)` với cùng điều kiện, `location` là trung bình toạ độ → `{ mode: "CLUSTERS", items: [{ location, count }] }`.
- [x] Chi tiết: `id`, `signTypeCode`, `categoryCode`, `location`, `headingDeg`, `status`, `confidence`, `confirmCount`, `denyCount`, `updatedAt`, `coverCropUrl` (`presignedGet(SIGN_CROPS, key, 10 phút)` hoặc `null`). Không có hoặc chưa công bố → `404`.

### Nhiệm vụ 4 · Controller

**Tạo:** `sign/web/SignMapController.java`; test `SignMapControllerTest` (`@WebMvcTest`).

- [x] `GET /api/v1/signs`, `GET /api/v1/signs/{id}`.
- [x] Test: tham số hợp lệ chuyển đúng tới dịch vụ, mặc định `zoom = 14`; `bbox` sai → `400` có `details`; JSON hai chế độ đúng khuôn `docs/07 §6`; chi tiết không có → `404`.

### Nhiệm vụ 5 · Test tích hợp

**Tạo:** `src/test/java/vn/signa/sign/MapIT.java`, `MapPerformanceIT.java`. Dữ liệu chèn bằng SQL trong test, mã nhóm, mã loại và vùng riêng cho từng ca để không lẫn với dữ liệu của test khác.

- [x] IT-B06: biển trong và ngoài `bbox`, chỉ biển trong được trả; lọc theo nhóm, theo mã, cả hai.
- [x] IT-B07: 600 biển khớp bộ lọc → `mode = CLUSTERS`, tổng `count` = 600.
- [x] Biển `PENDING`, `REJECTED`, `REMOVED` không xuất hiện; chi tiết của chúng trả `404`.
- [x] Thứ tự trục: lat 21.0285, lng 105.8048 trả đúng.
- [x] Chi tiết có `coverCropUrl` ký cho `sign-crops` khi có ảnh, `null` khi không.
- [x] NFR-03: 500 biển, gọi 50 lần, in p95 ra log, không assert cứng.
- [x] `./gradlew spotlessCheck test compileTestJava` thành công; markdownlint sạch; commit.

## Phần 2 · Sau khi merge lát 1, 2, 3

### Nhiệm vụ 6 · Ghép lát 1, 2, 3

- [x] `git merge --no-ff backend/3-recognition`, không có xung đột văn bản. Sửa để build xanh ngay trong commit ghép: `PermissionMatrixTest` nạp mọi controller nên cần `@MockitoBean MapService`; `SignMapControllerTest` thêm `@WithApiSecurity`.

### Nhiệm vụ 7 · Quyền công khai

- [x] `SecurityConfig` lát 1 đã `permitAll` cho `GET /api/v1/signs/**` (khớp cả `/api/v1/signs`). Dòng `Endpoint.reserved(GET /api/v1/signs)` trong `PermissionMatrixTest` đổi thành hai dòng `Endpoint.of(..., Access.PUBLIC)` cho `GET /signs?bbox=…` và `GET /signs/{id}`, có stub `MapService`; khách và mọi vai trò nhận `200`.
- [x] `SignMapControllerTest` chạy với chuỗi Security thật, không gửi token.

### Nhiệm vụ 8 · Dữ liệu mẫu

**Tạo:** `src/test/java/vn/signa/sign/map/DemoMapIT.java`.

- [x] Nạp `db/demo/R__demo_data.sql` của lát 2 rồi gọi `GET /signs` trên `bbox` Hà Nội: đúng 24 biển mẫu `APPROVED` + `UNDER_REVIEW`, không có `PENDING`; lọc `PROHIBITORY` khớp CSDL; chi tiết biển `PENDING` trả `404`.
- [x] Không bật profile `demo` cho context IT: Flyway sẽ ghi dòng `R__` vào lịch sử migration dùng chung và các context khác (không có `classpath:db/demo`) báo migration thiếu. Nạp file bằng tài khoản migrator như `DemoDataIT`, xoá biển mẫu sau mỗi ca. Chỉ so biển có UUID mẫu vì `MapIT`, `SchemaIT` cũng chèn biển ở Hà Nội.

### Nhiệm vụ 9 · Cache-Control

- [x] `GET /signs`: `no-cache`. Biển đổi trạng thái ngay khi duyệt, web quản trị tải lại bản đồ sau khi sửa; chưa có ETag nên client hỏi lại mỗi lần. Dữ liệu công khai nên vẫn cho lưu, khác `no-store` mặc định của Spring Security; cùng cách lát 2 làm với danh mục.
- [x] `GET /signs/{id}`: `no-store`. Có URL ký 10 phút tới ảnh riêng tư; từ lát 7 có đánh giá của người đang đăng nhập.
- [x] Lỗi (`400`, `404`) giữ header mặc định của Spring Security.

### Việc chuyển sang lát khác

- [ ] `myRating` trong `GET /signs/{id}` khi đã đăng nhập: việc của **lát 7** (đánh giá). Khi thêm, giữ `no-store` cho chi tiết.
- [ ] Ghi số p95 NFR-03 đo trên CI vào mục "Tiến độ"; nghiệm thu chính thức bằng k6 ở lát 10.

## Tiến độ

**2026-11-23.** Phần 1 đã commit trên `backend/4-map`, chưa push.

- Trên máy: `./gradlew spotlessCheck test compileTestJava` thành công, 71/71 unit test đạt (20 của lát 0, 51 mới: tham số 29, ô lưới 8, đa giác 3, dịch vụ 5, controller 6).
- Chỉ chạy trên CI (máy không có Docker): `MapIT` (IT-B06, IT-B07, trạng thái ẩn, thứ tự trục, vùng gần cả thế giới, chi tiết và URL ký) và `MapPerformanceIT` (p95 NFR-03, chưa có số, ghi vào đây sau lần chạy CI đầu tiên).
- Quyết định: tham số nhận dạng chuỗi và kiểm tra trong `MapQueryParser` để mọi lỗi có `details`; mã lọc bỏ trùng, giới hạn theo độ dài cột; WKT dùng số thập phân thường và chia cạnh đông–tây ≤ 90° để `bbox` rộng không bị MySQL nối vòng theo đường trắc địa; cạnh ô nới ra khi vượt 128 ô mỗi trục; ảnh đại diện ký lỗi thì trả `500`, không nuốt lỗi.
- Rủi ro: `worldWideBboxStillMatches` dựa trên cách MySQL tính MBR của đa giác địa lý, chưa kiểm chứng được trên máy; nếu đỏ trên CI cần xử lý vùng rộng riêng. Cạnh trắc địa hơi phồng về phía cực nên vùng rộng có thể lấy thừa biển sát mép. Truy vấn gom vẫn quét mọi biển trong vùng (kết quả có giới hạn, chi phí quét thì không). Web phải kẹp `bounds` về [-180, 180] ở zoom thấp, nếu không sẽ nhận `400`.

**2026-11-24.** Phần 2 đã commit trên `backend/4-map`, chưa push: commit ghép lát 1, 2, 3, ba commit cho nhiệm vụ 7–9 và một commit tiến độ.

- Trên máy: `./gradlew spotlessApply test compileTestJava` thành công, 341/341 unit test đạt sau khi ghép (gồm `PermissionMatrixTest` 100 ca, có 8 ca mới cho `/signs`).
- Chỉ chạy trên CI: `DemoMapIT`, cùng `MapIT`, `MapPerformanceIT` của phần 1.
- Rủi ro: `DemoMapIT` và `DemoDataIT` cùng nạp và xoá biển mẫu; JUnit chạy tuần tự nên không đụng nhau, nếu bật chạy song song thì phải tách. Số p95 NFR-03 vẫn chưa có.
