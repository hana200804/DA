# Lát 2 · Danh mục biển

> Kế hoạch thực thi theo [thiết kế triển khai](../thiet-ke-trien-khai.md) §5.3 hàng "Danh mục nhãn" và §6 hàng "2 · Danh mục". Đánh dấu `- [x]` khi xong từng bước để người khác (hoặc Codex) làm tiếp được.

**Mục tiêu:** nạp danh mục nhãn `ai/data/labels.yaml` vào `sign_categories`, `sign_types`; API công khai tra cứu danh mục có ETag (FR-08); interface `catalog.api.SignCatalog` cho lát 3, 4, 5; dữ liệu biển mẫu cho profile `demo`. Phần quản trị danh mục (FR-35) làm sau khi merge lát 1.

**Công nghệ:** như [lát 0](lat-0-khung.md). Đọc YAML bằng SnakeYAML (có sẵn qua Spring Boot), cache bằng `StringRedisTemplate`, JSON bằng `JsonMapper` của Jackson 3.

## Ràng buộc chung

- Theo [quy tắc làm song song](../quy-tac-song-song.md): phần 1 chỉ gồm phần không cần đăng nhập. Migration của lát này dùng `V20__` tới `V29__`.
- `catalog` chỉ viết SQL trên `sign_categories`, `sign_types`. Module khác chỉ dùng `catalog.api`.
- Lỗi theo [API §2](../../07-api.md#2-định-dạng-lỗi). Thời gian lấy từ bean `Clock`.
- Nạp nhãn không bao giờ ghi đè nội dung quản trị viên đã sửa: `description`, `icon_key`, `active`, và `name` khi đã khác tên lấy từ file.

## Chia phần

| Phần | Nội dung | Điều kiện |
| --- | --- | --- |
| 1 | Nạp nhãn, `SignCatalog`, `GET /sign-categories`, `GET /sign-types`, `GET /sign-types/{code}`, `R__demo_data.sql` | Làm ngay, song song với lát 1, 3, 5 |
| 2 | API quản trị FR-35, ảnh minh hoạ, nối `AuditRecorder` | Sau khi nhánh đã merge `backend/1-identity` |

## Interface `catalog.api.SignCatalog`

Lát 3 và lát 5 dùng interface này. Không đổi chữ ký khi chưa báo các lát đó.

```java
public interface SignCatalog {
  /** Loại biển theo mã, kể cả loại đã ngừng dùng (xem {@code SignTypeView.active()}). */
  Optional<SignTypeView> findType(String code);

  /** Mã nhóm của loại biển, kể cả loại đã ngừng dùng; rỗng nếu không có mã này. */
  Optional<String> categoryOf(String code);

  /** Loại đang dùng, có trong danh mục nhãn và {@code label_map_version} bằng đúng phiên bản này. */
  boolean isActiveRecognizable(String code, int labelMapVersion);

  /** Các phiên bản danh mục nhãn của loại đang dùng và nhận diện được. */
  Set<Integer> supportedLabelMapVersions();
}

public record SignTypeView(
    String code, String categoryCode, String name, String description, Integer speedKmh,
    String iconKey, Integer labelId, Integer labelMapVersion, boolean active) {
  public boolean recognizable();   // labelId != null
  public String iconUrl();         // "/icons/" + iconKey, hoặc null
}
```

## Điểm cần soi khi review

1. Nạp nhãn hai lần cho cùng kết quả, lần hai không đổi `updated_at`, không ghi lịch sử.
2. `description`, `icon_key` quản trị viên đã sửa giữ nguyên sau khi nạp lại; `name` chỉ đổi khi đang bằng tên cũ từ file.
3. `labels.yaml` trùng `id`, trùng `code`, nhóm không tồn tại, thiếu trường → ứng dụng dừng khởi động, thông báo có số dòng.
4. `GET /sign-types` với `If-None-Match` khớp → `304`, không có body. ETag đổi khi nội dung đổi.
5. Loại đã ngừng dùng không có trong `GET /sign-types`, và `GET /sign-types/{code}` trả `404`.
6. Redis lỗi thì API vẫn trả dữ liệu từ MySQL.

## Phần 1

### Nhiệm vụ 1 · Kế hoạch

- [x] Viết kế hoạch này, commit.

### Nhiệm vụ 2 · Đọc và kiểm tra `labels.yaml`

**Tạo:** `catalog/internal/LabelMap.java`, `LabelsYamlParser.java`, `LabelMapValidator.java`, `LabelsFileException.java`, `src/test/.../catalog/internal/LabelsYamlParserTest.java`, `LabelMapValidatorTest.java`.

- [x] Parser đọc cây node của SnakeYAML (`Yaml.compose` với `SafeConstructor`, không dựng đối tượng tuỳ ý) để giữ số dòng của từng mục.
- [x] Đổi khoá nhóm sang chữ hoa (`prohibitory` → `PROHIBITORY`); `sort_order` là thứ tự nhóm trong file, bắt đầu từ 1.
- [x] Validator gom mọi lỗi rồi ném một `LabelsFileException`: trùng `id`, trùng `code`, `category` không có trong `categories`, thiếu `name`, `id` ngoài `0..32767`, `speed_kmh` không dương, mã nhóm sai mẫu `^[A-Z][A-Z0-9_]{0,19}$`. Mỗi lỗi ghi `dòng N`.
- [x] Unit test dùng file thật `../ai/data/labels.yaml`: phiên bản 1, 46 lớp, 5 nhóm, 4 biển tốc độ có `speed_kmh`. Test lỗi bằng YAML viết trong test.
- [x] Commit.

### Nhiệm vụ 3 · Nạp nhãn

**Tạo:** `src/main/resources/db/migration/V20__sign_types_label_name.sql`, `catalog/internal/CatalogProperties.java`, `CatalogRepository.java`, `LabelImporter.java`, `LabelImportRunner.java`, `CatalogAuditPort.java`, `LoggingCatalogAuditPort.java`, `SignTypesCache.java`, `src/test/.../catalog/internal/LabelImporterTest.java`, `src/test/.../catalog/LabelImportIT.java`.

- [x] `V20`: thêm cột `sign_types.label_name` (tên lấy từ file ở lần nạp gần nhất). Cột này cho biết quản trị viên đã sửa `name` hay chưa.
- [x] `LabelImporter.importFrom(Path)` chạy trong một giao dịch. Nhóm chưa có thì thêm; nhóm đã có giữ nguyên. Loại chưa có thì thêm. Loại đã có thì cập nhật `category_code`, `speed_kmh`, `label_id`, `label_map_version`, `label_name`, và `name` khi `name = label_name`. Không đụng `description`, `icon_key`, `active`. Chỉ `UPDATE` khi có trường đổi.
- [x] Ghi lịch sử qua `CatalogAuditPort`: `CREATED` cho nhóm, loại mới; `UPDATED` với các trường đổi cho loại được cập nhật. `actor_type = SYSTEM`.
- [x] Xoá `cache:sign-types` sau khi giao dịch commit.
- [x] `LabelImportRunner` chạy khi `signa.catalog.import-on-startup=true` hoặc có tham số `--import-labels`, không chạy ở profile `migrate`. File lỗi thì ném lỗi, ứng dụng dừng khởi động.
- [x] `LabelImporterTest` (kho trong bộ nhớ, chạy trên máy) và `LabelImportIT`: nạp hai lần cho cùng kết quả; giữ `description`, `icon_key`, `name` đã sửa; đổi `name` khi đang bằng tên cũ từ file. Chỉ compile trên máy, chạy trên CI.
- [x] Commit.

### Nhiệm vụ 4 · `SignCatalog` và API công khai

**Tạo:** `catalog/api/SignCatalog.java`, `SignTypeView.java`, `catalog/internal/JdbcSignCatalog.java`, `CatalogQueryService.java`, `ETags.java`, `CatalogResponses.java`, `catalog/web/CatalogController.java`, `src/test/.../catalog/internal/ETagsTest.java`, `CatalogQueryServiceTest.java`, `JdbcSignCatalogTest.java`, `src/test/.../catalog/web/CatalogControllerTest.java`, `src/test/.../catalog/CatalogApiIT.java`.

- [x] `GET /api/v1/sign-categories` → `{ items: [{ code, name, description, sortOrder }] }`, sắp theo `sortOrder`.
- [x] `GET /api/v1/sign-types` → `{ labelMapVersion, items: [{ code, categoryCode, name, description, speedKmh, iconUrl, recognizable }] }`, chỉ loại `active`, sắp theo `code`. `labelMapVersion` là phiên bản lớn nhất trong các loại đang dùng. Body JSON được cache ở Redis `cache:sign-types` (TTL 24 giờ để tự lành nếu xoá cache lỗi). ETag mạnh là 128 bit đầu của SHA-256 của body. `If-None-Match` khớp → `304` (Spring tự xử lý khi `ResponseEntity` có ETag). `Cache-Control: no-cache` để client luôn hỏi lại.
- [x] `GET /api/v1/sign-types/{code}` → một loại, `404 NOT_FOUND` nếu không có hoặc đã ngừng dùng.
- [x] `iconUrl` là `/icons/{icon_key}` hoặc `null`; `recognizable = label_id != null`.
- [x] Redis lỗi thì ghi log cảnh báo và đọc MySQL.
- [x] Unit test ETag; `@WebMvcTest` cho controller; `CatalogApiIT`: `304` khi `If-None-Match` khớp, loại ngừng dùng không xuất hiện và trả `404`, `SignCatalog` trả đúng. IT chỉ chạy trên CI.
- [x] Commit.

### Nhiệm vụ 5 · Dữ liệu biển mẫu

**Tạo:** `src/main/resources/db/demo/R__demo_data.sql`, `src/main/resources/application-demo.yml`, `src/test/.../catalog/DemoDataIT.java`.

- [x] Profile `demo` thêm `classpath:db/demo` vào `spring.flyway.locations`. Chạy cùng `migrate`: `--spring.profiles.active=migrate,demo`.
- [x] Tự chèn 5 nhóm và các loại biển dùng trong dữ liệu mẫu, giống hệt `labels.yaml` v1, bỏ qua nếu đã có. Flyway chạy trước khi nạp nhãn nên không phụ thuộc thứ tự.
- [x] `DemoDataIT` chạy file hai lần bằng `ResourceDatabasePopulator` (không qua Flyway để không thêm dòng `R__` vào lịch sử migration dùng chung), kiểm 18/6/6 trạng thái, toạ độ trong khung, nạp nhãn sau đó không đổi gì. Chỉ chạy trên CI.
- [x] 30 biển quanh Hà Nội (vĩ độ 21.02–21.04, kinh độ 105.80–105.85): 18 `APPROVED`, 6 `UNDER_REVIEW`, 6 `PENDING`. UUID cố định, chạy lại được (`ON DUPLICATE KEY UPDATE`). Toạ độ theo `docs/06 §9`.
- [x] Commit.

### Nhiệm vụ 6 · Tài liệu và kiểm tra cuối

- [x] `docs/06` §5 thêm cột `label_name`. `docs/07` §4 ghi ETag, `304`, `404`. `backend/README.md` ghi `SIGNA_CATALOG_IMPORT_ON_STARTUP`, `--import-labels`, profile `demo`.
- [x] `./gradlew spotlessCheck test compileTestJava` thành công; markdownlint sạch; commit.

## Phần 2 · Sau khi merge lát 1

- [x] Ghép `backend/1-identity` và chạy `./gradlew spotlessApply test compileTestJava`.
- [x] Nạp nhãn gọi trực tiếp `AuditRecorder.record(AuditEntry.bySystem(...))` trong giao dịch của catalog. Xoá cổng tạm. Thao tác quản trị dùng `AuditEntry.byUser(...)`.
- [x] `GET /api/v1/admin/sign-types?active=`: trả `{ items: [...] }` gồm mọi loại biển khi bỏ tham số, kể cả loại đã ngừng dùng. Mỗi loại có `code`, `categoryCode`, `name`, `description`, `speedKmh`, `iconUrl`, `recognizable`, `active`, `updatedAt`.
- [x] `POST /api/v1/admin/sign-categories`, `PATCH /api/v1/admin/sign-categories/{code}`: mã chuẩn hoá chữ hoa, mẫu `^[A-Z][A-Z0-9_]{0,19}$`, tên 1–100 ký tự, `sortOrder` trong `SMALLINT` (khớp `web-admin/src/catalogTypes.ts`).
- [x] `POST /api/v1/admin/sign-types`: mã dài 1–30 ký tự và luôn tạo loại `recognizable = false` (`label_id` rỗng). Tạo nhóm hoặc loại trùng mã trả `409 CONFLICT`.
- [x] `PATCH /api/v1/admin/sign-types/{code}`: sửa `name`, `description`, `categoryCode`, `speedKmh`, `active`. Ngừng dùng bằng `active = false`.
- [x] `PUT /api/v1/admin/sign-types/{code}/icon`: multipart part `icon`, PNG/JPEG/WebP ≤ 1 MB, kiểm tra chữ ký tệp, lưu `sign-icons/{code}.{ext}`, cập nhật `icon_key` và dọn ảnh cũ khi khoá đổi.
- [x] Ghi lịch sử `actor_type = USER` với giá trị trước, sau trong cùng giao dịch. Xoá `cache:sign-types` sau commit.
- [x] Thêm sáu endpoint quản trị vào `PermissionMatrixTest`. Bổ sung `@WebMvcTest` và `CatalogAdminIT` cho luồng thay đổi, lịch sử, `403` của `MODERATOR` và `415` khi ảnh giả mạo.
- [x] Đặt `Cache-Control: no-cache` tường minh ở cả ba GET công khai. Kiểm thử qua chuỗi Spring Security thật, gồm cả phản hồi `304` khi ETag khớp.
- [ ] Web trang danh mục dùng danh sách quản trị để tải cả loại đã ngừng dùng. Trang hiện tải danh sách công khai.

## Tiến độ

Ghi ở đây trạng thái cuối và việc còn dở khi dừng giữa chừng.

**2026-11-23.** Bắt đầu phần 1 trên `backend/2-catalog` (đã có lát 0).

- Nhánh chưa có `audit.api.AuditRecorder` (lát 1 tạo). Phần 1 dùng interface tạm `catalog.internal.CatalogAuditPort` cùng cài đặt chỉ ghi log `LoggingCatalogAuditPort`; phần 2 nối vào `AuditRecorder`.

**2026-11-23, kết thúc phần 1.** Phần 1 xong trên `backend/2-catalog`, 5 nhiệm vụ đã commit.

- Worker đầu bị dừng giữa nhiệm vụ 6 vì máy hết RAM. Worker sau kiểm tra phần dở (tài liệu `docs/06`, `docs/07`, `backend/README.md`) và commit tiếp. Máy ít RAM: chạy một lệnh Gradle mỗi lúc.
- `./gradlew spotlessCheck test compileTestJava` thành công: 50 unit test đạt, 30 trong số đó thuộc `catalog`. `LabelImportIT`, `CatalogApiIT`, `DemoDataIT` chỉ compile trên máy, chạy trên CI (`./gradlew integrationTest`, cần Docker).
- Quyết định: thêm cột `sign_types.label_name` (V20) để biết quản trị viên đã sửa `name` hay chưa. Lệnh nạp không sửa nhóm đã có, không đụng `active`. ETag là 128 bit đầu của SHA-256 của body. Cache Redis có TTL 24 giờ, Redis lỗi thì đọc MySQL. Dữ liệu mẫu tự chèn nhóm và 14 loại biển giống `labels.yaml` v1.
- Còn chờ phần 2: nối `CatalogAuditPort` vào `audit.api.AuditRecorder`. Sau khi ghép lát 1, đặt `Cache-Control` tường minh cho mọi GET danh mục.
- Rủi ro: chưa chạy IT nào trên máy. `R__demo_data.sql` ghi vào bảng `signs` của module `sign`, chỉ ở profile `demo`, và cột của bảng này có thể đổi khi ghép lát 3.

**2026-11-24, kết thúc phần backend của phần 2.** Đã ghép lát 1, nối bộ ghi lịch sử, thêm header cache ở API công khai và triển khai sáu endpoint quản trị FR-35.

- Xung đột Git duy nhất ở `application.yml`. Kết quả ghép giữ cả cấu hình `identity` và `catalog`.
- `./gradlew spotlessApply test compileTestJava` đạt 235 ca, gồm 96 ca của ma trận quyền. `CatalogAdminIT` có ba ca đã biên dịch. Lần chạy tại máy này dừng ở bước khởi tạo Testcontainers do thiếu Docker.
- Việc còn lại là chạy `CatalogAdminIT` trên CI có Docker và nối danh sách quản trị vào web.
