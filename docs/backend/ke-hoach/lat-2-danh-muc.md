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

**Tạo:** `catalog/api/SignCatalog.java`, `SignTypeView.java`, `catalog/internal/JdbcSignCatalog.java`, `CatalogQueryService.java`, `SignTypesCache.java`, `ETags.java`, các record phản hồi, `catalog/web/CatalogController.java`, `src/test/.../catalog/internal/ETagsTest.java`, `CatalogQueryServiceTest.java`, `src/test/.../catalog/web/CatalogControllerTest.java`, `src/test/.../catalog/CatalogApiIT.java`.

- [ ] `GET /api/v1/sign-categories` → `{ items: [{ code, name, description, sortOrder }] }`, sắp theo `sortOrder`.
- [ ] `GET /api/v1/sign-types` → `{ labelMapVersion, items: [{ code, categoryCode, name, description, speedKmh, iconUrl, recognizable }] }`, chỉ loại `active`, sắp theo `code`. `labelMapVersion` là phiên bản lớn nhất trong các loại đang dùng. Body JSON được cache ở Redis `cache:sign-types` (TTL 24 giờ để tự lành nếu xoá cache lỗi). ETag mạnh là 128 bit đầu của SHA-256 của body. `If-None-Match` khớp → `304` (Spring tự xử lý khi `ResponseEntity` có ETag). `Cache-Control: no-cache` để client luôn hỏi lại.
- [ ] `GET /api/v1/sign-types/{code}` → một loại, `404 NOT_FOUND` nếu không có hoặc đã ngừng dùng.
- [ ] `iconUrl` là `/icons/{icon_key}` hoặc `null`; `recognizable = label_id != null`.
- [ ] Redis lỗi thì ghi log cảnh báo và đọc MySQL.
- [ ] Unit test ETag; `@WebMvcTest` cho controller; `CatalogApiIT`: `304` khi `If-None-Match` khớp, loại ngừng dùng không xuất hiện và trả `404`, `SignCatalog` trả đúng. IT chỉ chạy trên CI.
- [ ] Commit.

### Nhiệm vụ 5 · Dữ liệu biển mẫu

**Tạo:** `src/main/resources/db/demo/R__demo_data.sql`, `src/main/resources/application-demo.yml`.

- [ ] Profile `demo` thêm `classpath:db/demo` vào `spring.flyway.locations`. Chạy cùng `migrate`: `--spring.profiles.active=migrate,demo`.
- [ ] Tự chèn 5 nhóm và các loại biển dùng trong dữ liệu mẫu, giống hệt `labels.yaml` v1, bỏ qua nếu đã có. Flyway chạy trước khi nạp nhãn nên không phụ thuộc thứ tự.
- [ ] 30 biển quanh Hà Nội (vĩ độ 21.02–21.04, kinh độ 105.80–105.85): 18 `APPROVED`, 6 `UNDER_REVIEW`, 6 `PENDING`. UUID cố định, chạy lại được (`ON DUPLICATE KEY UPDATE`). Toạ độ theo `docs/06 §9`.
- [ ] Commit.

### Nhiệm vụ 6 · Tài liệu và kiểm tra cuối

- [ ] `docs/06` §5 thêm cột `label_name`. `docs/07` §4 ghi ETag, `304`, `404`. `backend/README.md` ghi `SIGNA_CATALOG_IMPORT_ON_STARTUP`, `--import-labels`, profile `demo`.
- [ ] `./gradlew spotlessCheck test compileTestJava` thành công; markdownlint sạch; commit.

## Phần 2 · Sau khi merge lát 1

Chưa làm. Cần `SecurityConfig`, quyền `catalog:manage` và `audit.api.AuditRecorder` của lát 1.

- [ ] Thay `LoggingCatalogAuditPort` bằng adapter gọi `AuditRecorder.record(AuditEntry.bySystem(...))` (và `byUser` cho thao tác quản trị), trong giao dịch của catalog.
- [ ] `GET /api/v1/admin/sign-types?active=`: mọi loại biển, kể cả loại đã ngừng dùng, dạng `{ code, categoryCode, name, description, speedKmh, iconUrl, recognizable, active, updatedAt }`.
- [ ] `POST /api/v1/admin/sign-categories`, `PATCH /api/v1/admin/sign-categories/{code}`: mã chuẩn hoá chữ hoa, mẫu `^[A-Z][A-Z0-9_]{0,19}$`, tên 1–100 ký tự, `sortOrder` trong `SMALLINT` (khớp `web-admin/src/catalogTypes.ts`).
- [ ] `POST /api/v1/admin/sign-types`: luôn tạo loại `recognizable = false`. Mã đã có trong danh mục nhãn hiện hành thì trả `409 CONFLICT` **(đề xuất)**, vì loại đó do lệnh nạp nhãn quản lý (FR-35).
- [ ] `PATCH /api/v1/admin/sign-types/{code}`: sửa `name`, `description`, `categoryCode`, `speedKmh`, `active`. Không có endpoint xoá; ngừng dùng bằng `active = false`.
- [ ] `PUT /api/v1/admin/sign-types/{code}/icon`: multipart part `icon`, PNG/JPEG/WebP ≤ 1 MB, kiểm tra magic byte, lưu `sign-icons/{code}.{ext}`, cập nhật `icon_key`, xoá ảnh cũ nếu khác khoá.
- [ ] Mỗi thay đổi ghi lịch sử `actor_type = USER` và xoá `cache:sign-types` sau commit.
- [ ] Thêm dòng vào `PermissionMatrixTest`; IT cho `403` khi thiếu `catalog:manage`.
- [ ] Web trang danh mục chạy với API thật.

## Tiến độ

Ghi ở đây trạng thái cuối và việc còn dở khi dừng giữa chừng.

**2026-11-23.** Bắt đầu phần 1 trên `backend/2-catalog` (đã có lát 0).

- Nhánh chưa có `audit.api.AuditRecorder` (lát 1 tạo). Phần 1 dùng interface tạm `catalog.internal.CatalogAuditPort` cùng cài đặt chỉ ghi log `LoggingCatalogAuditPort`; phần 2 nối vào `AuditRecorder`.
