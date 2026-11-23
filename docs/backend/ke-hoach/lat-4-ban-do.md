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

- [ ] `bbox=minLng,minLat,maxLng,maxLat`: đủ 4 số hữu hạn, `lng ∈ [-180, 180]`, `lat ∈ [-90, 90]`, `minLng < maxLng`, `minLat < maxLat`; `minLng > maxLng` báo riêng là vắt qua kinh tuyến 180°.
- [ ] `zoom` số nguyên 0–22, mặc định 14 khi vắng.
- [ ] `categories`, `signTypeCodes`: danh sách phân cách dấu phẩy, bỏ khoảng trắng và mục rỗng, bỏ trùng, tối đa 50 mục, mỗi mã không quá độ dài cột (20 và 30 ký tự).
- [ ] Gom mọi lỗi vào một `ApiException(VALIDATION_ERROR)` với `details[].field` là tên tham số.
- [ ] `GridCell.sizeDeg(zoom) = 360 / 2^zoom / 8`.
- [ ] WKT của `bbox` viết số dạng thập phân thường (không `1.0E-4`), cạnh đông–tây chia nhỏ để không cạnh nào vượt 90° kinh độ.

### Nhiệm vụ 3 · Truy vấn và dịch vụ

**Tạo:** `sign/internal/map/MapSignRepository.java`, `MapService.java`, các record kết quả.

- [ ] Truy vấn biển trong vùng theo `docs/06 §9`: `MBRContains(ST_GeomFromText(:polygon, 4326, 'axis-order=long-lat'), location)`, `status IN ('APPROVED','UNDER_REVIEW')`, lọc nhóm và mã khi có, `LIMIT 501`, đọc toạ độ bằng `ST_Latitude`, `ST_Longitude`.
- [ ] Tối đa 500 dòng → `{ mode: "SIGNS", items: [{ id, signTypeCode, categoryCode, location, headingDeg, status, confidence }] }`.
- [ ] Được 501 dòng → truy vấn gom `GROUP BY FLOOR(lng / cell), FLOOR(lat / cell)` với cùng điều kiện, `location` là trung bình toạ độ → `{ mode: "CLUSTERS", items: [{ location, count }] }`.
- [ ] Chi tiết: `id`, `signTypeCode`, `categoryCode`, `location`, `headingDeg`, `status`, `confidence`, `confirmCount`, `denyCount`, `updatedAt`, `coverCropUrl` (`presignedGet(SIGN_CROPS, key, 10 phút)` hoặc `null`). Không có hoặc chưa công bố → `404`.

### Nhiệm vụ 4 · Controller

**Tạo:** `sign/web/SignMapController.java`; test `SignMapControllerTest` (`@WebMvcTest`).

- [ ] `GET /api/v1/signs`, `GET /api/v1/signs/{id}`.
- [ ] Test: tham số hợp lệ chuyển đúng tới dịch vụ, mặc định `zoom = 14`; `bbox` sai → `400` có `details`; JSON hai chế độ đúng khuôn `docs/07 §6`; chi tiết không có → `404`.

### Nhiệm vụ 5 · Test tích hợp

**Tạo:** `src/test/java/vn/signa/sign/MapIT.java`, `MapPerformanceIT.java`. Dữ liệu chèn bằng SQL trong test, mã nhóm, mã loại và vùng riêng cho từng ca để không lẫn với dữ liệu của test khác.

- [ ] IT-B06: biển trong và ngoài `bbox`, chỉ biển trong được trả; lọc theo nhóm, theo mã, cả hai.
- [ ] IT-B07: 600 biển khớp bộ lọc → `mode = CLUSTERS`, tổng `count` = 600.
- [ ] Biển `PENDING`, `REJECTED`, `REMOVED` không xuất hiện; chi tiết của chúng trả `404`.
- [ ] Thứ tự trục: lat 21.0285, lng 105.8048 trả đúng.
- [ ] Chi tiết có `coverCropUrl` ký cho `sign-crops` khi có ảnh, `null` khi không.
- [ ] NFR-03: 500 biển, gọi 50 lần, in p95 ra log, không assert cứng.
- [ ] `./gradlew spotlessCheck test compileTestJava` thành công; markdownlint sạch; commit.

## Phần 2 · Sau khi merge lát 1, 2

Chỉ ghi lại, chưa làm trong đợt này.

- [ ] `myRating` trong `GET /signs/{id}` khi đã đăng nhập: thuộc lát 7 (đánh giá), cần filter phiên của lát 1.
- [ ] Chạy lại IT-B06, IT-B07 trên dữ liệu mẫu `R__demo_data.sql` của lát 2 (profile `demo`) thay cho dữ liệu chèn tay.
- [ ] Ghi số p95 NFR-03 đo trên CI vào mục "Tiến độ"; nghiệm thu chính thức bằng k6 ở lát 10.

## Tiến độ

Ghi ở đây trạng thái cuối và việc còn dở khi dừng giữa chừng.

**2026-11-23.** Đã viết kế hoạch.
