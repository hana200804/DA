# Lát 6 · Phê duyệt và sửa biển

> Kế hoạch thực thi theo [thiết kế triển khai](../thiet-ke-trien-khai.md) và quy tắc làm song song (`docs/backend/quy-tac-song-song.md` của worktree điều phối). Đánh dấu `- [x]` khi xong từng bước để người khác (hoặc Codex) làm tiếp được.

**Mục tiêu:** kiểm duyệt viên xem hàng đợi, mở chi tiết biển, duyệt, từ chối, giữ, gỡ, thêm và sửa biển trên bản đồ. Mọi thao tác ghi lịch sử trong cùng giao dịch và chống xử lý chồng bằng `version`.

**Yêu cầu:** FR-21 (thứ tự hàng đợi), FR-23, FR-31, FR-33, NFR-11. Kiểm thử IT-B04, IT-B05.

**Nguồn chuẩn:** `docs/04 §6`, `docs/03 §6.4`, `docs/06 §6`, `docs/06 §8`, `docs/07 §8` mục "Phê duyệt và biển", `docs/08`, kế hoạch lát 5 mục "Ghi chú cho lát 6". Phía web: `web-admin/src/reviewTypes.ts`, `mapTypes.ts`, `reviewApi.ts`.

## Ranh giới

- Lát này chạy song song với lát 7. Mã của lát 6 nằm ở `vn.signa.sign.internal.review` và `vn.signa.sign.web.admin`. Lát 7 dùng `vn.signa.sign.internal.feedback`.
- Không sửa repository của lát 5. Lát 6 có repository riêng trên `signs`, `sign_code_weights`, `submissions` trong gói `review`, vì các lớp của lát 5 là package-private.
- Dùng lại lõi `vn.signa.sign.clustering` (`Scoring`, `ClusteringEngine.memberUpdates`, `SignState`) và `MapQueryParser` của lát 4 để đọc `bbox`, `categories`.
- `SecurityConfig` và `PermissionMatrixTest` chỉ thêm dòng. Không cần migration. Nếu cần thì dùng `V60__`–`V69__`.

## Luật đã chốt khi tài liệu chưa nói rõ

Các luật dưới đây là lựa chọn của lát này, cần cả nhóm xem lại khi review.

| Chủ đề | Tài liệu | Cách làm |
| --- | --- | --- |
| Chốt mã | `docs/04 §6`: duyệt thì đề xuất khác mã `REJECTED`. Đề bài yêu cầu điểm không dưới 0,5 ngay sau khi duyệt bằng mã không dẫn đầu | Người điều phối chốt (thay cách đặt `W` về 0 của bản đầu): duyệt, giữ, thêm trực tiếp đặt `reviewed_at`, và `β` chỉ tính `W_c` của đề xuất có `received_at > reviewed_at` cùng đánh giá DENY có `updated_at > reviewed_at`. Xem `docs/04 §6` đoạn "Sau quyết định của kiểm duyệt viên". Đề xuất khác mã vẫn chuyển `REJECTED`. Không đổi dòng nào trong `sign_code_weights` |
| Điểm khi duyệt | `Scoring.score` | Tính bằng `Scoring.score(biển đã chốt, c)` với `M = 2`, sau khi `SignState.Builder.reviewed(now)` xoá bằng chứng phản đối cũ. Biển `PENDING` vừa duyệt có `β = 0,5`, điểm ≥ 2,5 / 3 ≈ 0,83. Thao tác của kiểm duyệt viên không tự chuyển biển sang `UNDER_REVIEW` |
| Mã không hợp lệ | "Mã mới phải là loại đang dùng" | Mã không có hoặc đã ngừng dùng trả `422 BUSINESS_RULE`. Lỗi cú pháp trả `400 VALIDATION_ERROR` |
| Sửa mã biển `PENDING` | `PATCH` áp dụng cho `PENDING` | Chỉ sửa được vị trí và hướng. Đổi mã trả `422 BUSINESS_RULE` vì worker tính lại mã dẫn đầu theo trọng số nên sẽ ghi đè mã đã sửa. Đổi mã khi duyệt dùng `approve` |
| Sửa vị trí | "Có `location` thì đặt `location_locked = true`" | Áp dụng cho mọi thao tác có `location` (duyệt, giữ, sửa, thêm) |
| Từ chối | `reject_reason` là `VARCHAR(500)`, `reason` dài tới 1000 | Đề xuất thành viên nhận `reject_reason` là lý do của kiểm duyệt viên, cắt còn 500 ký tự. Biển đặt `reviewed_by`, `reviewed_at`, `suggestion = NULL` |
| Gỡ | Lát 5: đề xuất xác nhận biển `UNDER_REVIEW` ở `GROUPED`, lát 6 cập nhật khi giữ hoặc gỡ | Gỡ: đề xuất `GROUPED` chuyển `REJECTED` với lý do "Biển đã bị gỡ". Đề xuất `ACCEPTED` giữ nguyên vì từng được chấp nhận. Gỡ cũng đặt `reviewed_by`, `reviewed_at` |
| Giữ | `M = 2`, `deny_since_review = 0` | Đặt thêm `reviewed_by`, `suggestion = NULL`. Đề xuất `GROUPED` cùng mã chuyển `ACCEPTED` |
| Sửa biển đã công bố | Không nói `M`, `reviewed_at` | `PATCH` không đổi trạng thái, `M`, `reviewed_at`, `deny_since_review`. Đổi mã trên biển `APPROVED` chốt mã như trên. Trên biển `UNDER_REVIEW`, đề xuất cùng mã giữ trạng thái tới khi giữ hoặc gỡ |
| Lịch sử | "Các trường thay đổi" | `before`, `after` chỉ gồm trường đổi trong `status`, `signTypeCode`, `categoryCode`, `location` (`{lat, lng}`), `headingDeg`, `confidence`. Thao tác đổi trạng thái luôn có `status`. `CREATED` có `before = null` và `after` đủ các trường |
| Thứ tự hàng đợi | `APPROVE` trước, rồi điểm giảm dần, rồi `created_at` tăng dần | Hoà nữa thì theo `id` để phân trang ổn định. `page` tính từ 0 như `GET /me/submissions`, `size` mặc định 20, tối đa 100 |
| Chi tiết | `GET /admin/signs/{id}` | Trả biển ở mọi trạng thái, vì web đọc lại biển sau khi sửa hoặc khi nhận `409`. `submissions` sắp theo `received_at` tăng dần. `codeWeights` gồm mọi mã kể cả mã có `W = 0`. Response `Cache-Control: no-store` |
| Biển gần | 50 m | `ST_Distance_Sphere` với cùng bán kính của lõi, lọc thô bằng `MBRContains`. Gần cực hoặc kinh tuyến 180° thì chỉ lọc theo vĩ độ |
| Ghi đồng thời | `UPDATE … WHERE id = ? AND version = ?` | Đọc biển bằng `SELECT … FOR UPDATE` theo `id` trong giao dịch `READ COMMITTED`, so `version` với request, rồi ghi bằng `UPDATE … WHERE id = ? AND version = ?`. Lệch `version` hoặc `0` dòng thì `409 SIGN_ALREADY_PROCESSED`. Sai trạng thái cũng `409 SIGN_ALREADY_PROCESSED` |

## Kiểm tra worker của lát 5

`SignRepository.candidates` của lát 5 đọc ứng viên bằng `SELECT … FOR UPDATE` trong giao dịch của worker và ghi bằng `WHERE id = ? AND version = ?`. Lát 6 khoá cùng dòng `signs` trước khi đụng tới đề xuất thành viên và trọng số, nên hai bên tuần tự hoá trên dòng biển:

- Kiểm duyệt viên commit trước: worker chờ khoá, đọc bản mới nhất (`APPROVED`), gán đề xuất như một xác nhận.
- Worker commit trước: kiểm duyệt viên khoá được dòng nhưng `version` đã tăng, nhận `409`, web tải lại và thử lại.

Thứ tự khoá không tạo chu trình: worker khoá đề xuất mới (chưa thuộc biển) rồi khoá biển, sau đó mới sửa đề xuất thành viên. Lát 6 khoá biển rồi mới sửa đề xuất thành viên.

## Chia việc

### Nhiệm vụ 0 · Kế hoạch

- [x] Viết kế hoạch, markdownlint sạch, commit riêng.

### Nhiệm vụ 1 · Luật thuần

**Tạo trong** `sign/internal/review/`: `ReviewRules`, `ReviewRequests`, `ReviewQueries`, `SignChange`.

- [x] Kiểm đầu vào: `version` ≥ 0, `signTypeCode` 1–30 ký tự sau khi trim, `location` trong miền, `headingDeg` 0–359 hoặc `null`, `reason` 1–1000 ký tự sau khi trim. `PATCH` cần ít nhất một thay đổi, phân biệt `headingDeg` bỏ qua với `headingDeg: null`.
- [x] Luật chuyển trạng thái: `approve`, `reject` cho `PENDING`; `keep` cho `UNDER_REVIEW`; `remove` cho `APPROVED`, `UNDER_REVIEW`; `PATCH` cho `PENDING`, `APPROVED`, `UNDER_REVIEW`.
- [x] Chốt mã (đề xuất, trọng số), điểm `Scoring`, cập nhật đề xuất thành viên bằng `ClusteringEngine.memberUpdates`, before/after của lịch sử.
- [x] Unit test cho từng luật, gồm ca duyệt bằng mã không dẫn đầu có điểm ≥ 0,5.
- [x] Commit.

### Nhiệm vụ 2 · Repository và dịch vụ

**Tạo:** `ReviewSignRepository`, `ReviewQueryRepository`, `ReviewSql`, `ReviewService`, `ReviewQueryService`, `ReviewViews`.

- [x] Hàng đợi: lọc `status`, `suggestion`, `categories`, `bbox`; sắp `APPROVE` trước, `confidence` giảm, `created_at` tăng, `id`; đếm `total`; `signName` từ `SignCatalog`.
- [x] Chi tiết: biển, `codeWeights`, đề xuất kèm `cropUrl` ký 10 phút, `coverCropUrl`, `nearbySigns` trong 50 m không gồm chính biển.
- [x] Ghi: khoá biển `FOR UPDATE`, so `version`, `UPDATE … WHERE id = ? AND version = ?`, cập nhật đề xuất và trọng số, ghi lịch sử qua `AuditRecorder` trong cùng giao dịch.
- [x] Unit test dịch vụ với repository giả: `409` khi lệch `version`, sai trạng thái, lịch sử `EDITED` rồi `KEPT`.
- [x] Commit.

### Nhiệm vụ 3 · Controller và phân quyền

**Tạo:** `sign/web/admin/ReviewQueueController`, `AdminSignController`; sửa `identity/web/SecurityConfig` (chỉ thêm dòng), `PermissionMatrixTest`.

- [x] Tám endpoint theo `docs/07 §8`, `@PreAuthorize` đúng quyền. `POST /admin/signs` trả `201 { id, version }`, còn lại trả `204`.
- [x] Thêm quy tắc URL vào `SecurityConfig` và dòng vào `PermissionMatrixTest`.
- [x] `@WebMvcTest` cho hai controller: đọc request, lỗi `400`, mã trả về.
- [x] Commit.

### Nhiệm vụ 4 · Test tích hợp

**Tạo:** `src/test/.../sign/internal/ReviewIT.java`, `ReviewRaceIT.java`, `ReviewItSupport.java`. IT nằm trong gói `sign.internal` để gọi thẳng `ClusteringProcessor` của lát 5.

- [x] IT-B04: hai kiểm duyệt viên duyệt cùng `version`, một `204`, một `409 SIGN_ALREADY_PROCESSED`.
- [x] IT-B05: duyệt, sửa, gỡ một biển, đúng 3 dòng `audit_logs` với before/after đúng.
- [x] Duyệt bằng mã không dẫn đầu, điểm ≥ 0,5. Hàng đợi sắp đúng thứ tự. `nearbySigns` không gồm chính biển.
- [x] Đua với worker: duyệt trước rồi worker gán, worker gán trước rồi duyệt nhận `409` và thử lại, và chạy đồng thời nhiều lượt. Cả hai thay đổi đều còn.
- [x] Chỉ biên dịch trên máy, chạy trên CI. Commit.

### Nhiệm vụ 5 · Tài liệu và kiểm tra cuối

- [x] `docs/07 §8` ghi mã trả về, lỗi và các luật đã chốt của mục "Phê duyệt và biển".
- [x] `./gradlew spotlessCheck test compileTestJava jacocoTestCoverageVerification` exit 0; markdownlint sạch; ghi "Tiến độ"; commit.

## Hợp đồng cho lát 7

Lát 7 tính lại điểm khi có đánh giá bằng `ClusteringEngine.applyRating`, nên phải nạp `SignState` đủ hai giá trị dưới đây bằng SQL, giống worker gom cụm (`SignRepository.candidates`, `SubmissionRepository.weightsSinceReview`) và `ReviewSignRepository.lock`.

```java
package vn.signa.sign.clustering;

public record SignState(
    /* …các trường cũ… */
    int denySinceReview,                         // R− sau reviewed_at
    /* … */
    Instant reviewedAt,
    int version,
    List<MemberSubmission> members,
    Map<String, BigDecimal> weightsSinceReview) { // W_c sau reviewed_at, theo mã

  public static final class Builder {
    public Builder weightsSinceReview(Map<String, BigDecimal> v);
    public Builder addWeightSinceReview(String code, BigDecimal w);
    /** Quyết định mới: reviewedAt = at, weightsSinceReview rỗng, denySinceReview = 0. */
    public Builder reviewed(Instant at);
  }
}

public final class Scoring {
  /** α = 0,5 + W_c* + R+ + M; β = 0,5 + opposingWeight + denies. */
  public static BigDecimal score(SignState sign, String leading);
  /** reviewedAt rỗng: Σ_{c≠c*} W_c; ngược lại Σ_{c≠c*} weightsSinceReview. */
  public static BigDecimal opposingWeight(SignState sign, String leading);
  /** reviewedAt rỗng: denyCount; ngược lại denySinceReview. */
  public static int denies(SignState sign);
}
```

SQL nạp hai giá trị, `reviewed_at` của chính biển:

```sql
-- denySinceReview
SELECT COUNT(*) FROM sign_ratings r
WHERE r.sign_id = :id AND r.value = 'DENY'
  AND (signs.reviewed_at IS NULL OR r.updated_at > signs.reviewed_at);

-- weightsSinceReview
SELECT s.sign_type_code, SUM(s.weight)
FROM submissions s JOIN signs g ON g.id = s.sign_id
WHERE s.sign_id = :id AND s.weight IS NOT NULL
  AND g.reviewed_at IS NOT NULL AND s.received_at > g.reviewed_at
GROUP BY s.sign_type_code;
```

- `weightsSinceReview` khác rỗng khi `reviewedAt` rỗng thì `SignState` ném `IllegalArgumentException`.
- `applyRating` coi đánh giá là "sau duyệt" khi thời điểm **lớn hơn hẳn** `reviewedAt`, khớp `updated_at > reviewed_at`. Bản trước dùng "không trước".
- Mọi chỗ đặt `reviewedAt` mới phải dùng `Builder.reviewed(at)` để không mang bằng chứng phản đối cũ sang mốc mới.

## Tiến độ

Ghi ở đây trạng thái cuối và việc còn dở khi dừng giữa chừng.

**2026-11-24.** Bắt đầu trên `backend/6-review`, đã chứa lát 1–5 (`backend/5-submissions-clustering` ở `899606e`, không cần merge thêm).

**2026-11-24 · Xong.** NV0–NV5 đã commit trên `backend/6-review`, chưa push.

- `./gradlew spotlessCheck test compileTestJava jacocoTestCoverageVerification` trả exit 0 với 517 unit test đạt (lát 5 có 426). Lát này thêm 91 ca: `ReviewRulesTest` 20, `ReviewServiceTest` 9, `ReviewRequestsTest` 7, `ReviewQueriesTest` 4, `ReviewQueryServiceTest` 3, `AdminSignControllerTest` 12, `ReviewQueueControllerTest` 4 và 32 ca mới của `PermissionMatrixTest` (8 endpoint × 4 vai trò). Test kiến trúc vẫn đạt.
- Chỉ chạy được trên CI vì máy không có Docker: `ReviewIT` 7 ca (IT-B04, IT-B05 kèm kiểm `signa_app` không `UPDATE`, `DELETE` được `audit_logs`, duyệt bằng mã không dẫn đầu, thứ tự và bộ lọc hàng đợi, `nearbySigns`, từ chối và giữ, thêm biển) và `ReviewRaceIT` 3 ca (duyệt trước rồi worker gán, worker gán trước rồi duyệt nhận `409` và thử lại, 8 lượt chạy đồng thời). Hai lớp này đã biên dịch.
- Worker của lát 5 đã khoá ứng viên bằng `SELECT … FOR UPDATE` và ghi bằng `WHERE id = ? AND version = ?`, nên không sửa mã lát 5. Lát 6 khoá cùng dòng `signs` trước khi đụng đề xuất thành viên và trọng số (xem mục "Kiểm tra worker của lát 5").
- Không sửa repository nào của lát 5, không thêm migration, không sửa `shared/`. `SecurityConfig` chỉ thêm năm quy tắc URL trước `anyRequest()`. Controller vẫn có `@PreAuthorize` như các lát trước. `ReviewSql` lặp lại vài hàm chuyển kiểu của `SignSql` vì lớp này package-private.
- Quyết định tự đưa ra nằm ở mục "Luật đã chốt khi tài liệu chưa nói rõ". Ba điểm cần nhóm xem lại:
  - Chốt mã đưa `W` của mã khác về 0 để đạt yêu cầu điểm không dưới 0,5. Người điều phối không đồng ý; đã thay, xem mục ngày 2026-11-24 bên dưới.
  - `PATCH` đổi mã biển `PENDING` trả `422`.
  - `PATCH` có giá trị trùng hệt biển hiện tại trả `422`.
- Đã ghi mã trả về, bảng trạng thái và lỗi vào `docs/07 §8` mục "Phê duyệt và biển". Sơ đồ `docs/03 §6.4` đã sửa thành `204` ở lần sửa sau.
- Rủi ro:
  - Đã xử lý ở lần sửa sau: giữ biển có nhiều đánh giá "không đúng" từng có thể cho điểm dưới 0,5.
  - `ReviewRaceIT` chưa chạy thật. Nếu CI báo deadlock, kiểm thứ tự khoá giữa `submissions` và `signs` trước tiên.
  - `nearbySigns` gần kinh tuyến 180° hoặc gần cực bỏ lọc `MBRContains` và chỉ lọc theo vĩ độ, nên chậm hơn. Ngoài vùng đó vẫn đúng.
  - Lát 7 cũng thêm quy tắc vào cùng chỗ trong `SecurityConfig` và `PermissionMatrixTest`, nên khi ghép sẽ xung đột dạng chỉ thêm dòng.

**2026-11-24 · Đổi luật điểm sau quyết định.** Người điều phối không đồng ý đặt `W` của mã khác về 0, vì cách đó xoá bằng chứng và lệch `docs/04 §6`. Đã thay bằng quy tắc "bằng chứng phản đối trước `reviewed_at` coi như đã xem xét".

- Commit: `e37b72a` sửa lõi `clustering` và SQL của worker, `8eba236` bỏ đặt `W` về 0 ở lát 6, `9347412` `docs/04 §6`, `93e03d5` `docs/03 §6.4`, và commit tài liệu này.
- Đã sửa mã lát 5, được người điều phối cho phép:
  - `SignState` thêm `weightsSinceReview`, `Builder.reviewed(at)`, `Builder.addWeightSinceReview`.
  - `Scoring.score` tính `β` theo mốc `reviewedAt`, thêm `opposingWeight`, `denies`.
  - `ClusteringEngine` cộng `w` vào `weightsSinceReview` khi đề xuất nhận sau `reviewedAt`. Tự duyệt dùng `reviewed(now)` và tính lại điểm. `applyRating` so "sau duyệt" nghiêm ngặt.
  - `SignRepository.candidates` đếm `deny_since_review` từ `sign_ratings` bằng truy vấn con, chỉ đổi SQL, không đổi chữ ký. `SubmissionRepository` thêm hàm `weightsSinceReview(ids)`. `ClusteringProcessor` nạp thêm giá trị này.
  - Hai test cũ của lát 5 (`ClusteringEngineTest.confirmationCanTriggerReview`, `RatingTest.lowScoreTriggersReview`) dựng bằng chứng phản đối trước lần duyệt. Đã đánh dấu bằng chứng đó là sau duyệt để giữ đúng tình huống cần kiểm.
- Lát 6: bỏ `Outcome.weights`, `ReviewSignRepository.saveWeights` và phần khôi phục `W_c` từ đề xuất. Duyệt, giữ, thêm, từ chối, gỡ đều đặt mốc bằng `reviewed(now)`. `ReviewSignRepository.lock` nạp hai giá trị bằng SQL như worker.
- Test mới, không cần Docker: `ReviewedEvidenceTest` 9 ca trong gói `clustering`, `ReviewEvidenceChainTest` 1 ca đi trọn chuỗi duyệt B (A có W 1,8, B có W 0,9) → điểm 0,8718 và `sign_code_weights` không đổi → một CONFIRM, vẫn `APPROVED`, điểm 0,8958 → ba DENY mới, chuyển `UNDER_REVIEW` → giữ, điểm 0,8958. `ReviewRulesTest` thay ca khôi phục trọng số bằng ca giữ biển có 5 DENY cũ, điểm 0,8718.
- `./gradlew spotlessApply test compileTestJava jacocoTestCoverageVerification` trả exit 0 với 527 unit test đạt. Độ phủ gói `clustering` là 98,9% dòng (555/561) và 94,7% nhánh (270/285).
- `ReviewIT` sửa kỳ vọng: `W` của mã A giữ 2,7 sau khi duyệt bằng B. IT vẫn chỉ biên dịch trên máy và chờ CI.
- Rủi ro:
  - Truy vấn con đếm DENY chạy mỗi lần worker nạp ứng viên. `sign_ratings` có khoá chính `(sign_id, user_id)` nên tra theo `sign_id` dùng được chỉ mục.
  - Cột `deny_since_review` giờ là bản sao của giá trị tính từ `sign_ratings`. Worker và lát 6 ghi lại giá trị đã tính nên cột tự khớp sau mỗi lần ghi.
