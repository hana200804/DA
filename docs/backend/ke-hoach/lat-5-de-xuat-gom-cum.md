# Lát 5 · Đề xuất và gom cụm

> Kế hoạch thực thi theo [thiết kế triển khai](../thiet-ke-trien-khai.md) và quy tắc làm song song (`docs/backend/quy-tac-song-song.md` của worktree điều phối, chưa có trên nhánh này). Đánh dấu `- [x]` khi xong từng bước để người khác (hoặc Codex) làm tiếp được.

**Mục tiêu:** đề xuất của người dùng được gom thành biển, có trọng số, điểm tin cậy Beta, gợi ý cho kiểm duyệt viên, vị trí trung vị và chuyển trạng thái đúng [Thiết kế AI §6](../../04-thiet-ke-ai.md#6-gom-cụm-và-điểm-tin-cậy).

**Yêu cầu:** FR-04, FR-20, FR-21; cung cấp hàm thuần cho FR-22 (lát 7), FR-25 (lát 9) và cập nhật đề xuất thành viên khi duyệt (lát 6).

**Nguồn chuẩn:** `docs/04 §6`, ADR-0004, ADR-0007, `docs/06 §6`, `docs/08 §2.2`, `docs/backend/thiet-ke-trien-khai.md §5.3`.

## Chia phần

| Phần | Nội dung | Phụ thuộc | Khi nào |
| --- | --- | --- | --- |
| 1 | Lõi logic thuần trong `vn.signa.sign.clustering`: gán, trọng số, điểm, gợi ý, tự duyệt, rà soát lại, vị trí, chuyển trạng thái đề xuất | Lát 0 | Đợt song song, làm ngay |
| 2 | `POST /submissions`, repository JDBC, worker Redis Stream, quét lại, dọn ảnh, metric | Lát 1 (đăng nhập), lát 2 (`SignCatalog`) | Sau khi nhánh đã merge lát 1 và lát 2 |

## Ràng buộc chung

- Phần 1 là Java thuần: không Spring, không JDBC, không I/O. Test `ClusteringPurityTest` (ArchUnit) chặn phụ thuộc vào `org.springframework..`, `java.sql..`, `javax.sql..`, `java.io..`, `java.nio..`, `java.net..`.
- Mọi unit test chạy không cần Docker.
- `build.gradle.kts` chỉ thêm khối mới ở cuối file, kèm chú thích `// lát 5`.
- Không sửa `shared/`, luật test kiến trúc, `SignaProperties`.

## Luật đã chốt khi tài liệu chưa nói rõ

Các luật dưới đây là lựa chọn của lát này, cần cả nhóm xem lại khi review.

| Chủ đề | Tài liệu | Cách làm |
| --- | --- | --- |
| Bán kính Trái Đất | Thiết kế chỉ nói "gần `ST_Distance_Sphere`" | Haversine với R = 6 370 986 m, đúng bán kính mặc định của `ST_Distance_Sphere` trong MySQL. Ở 20 m, khác biệt so với R = 6 371 008,8 m dưới 0,1 mm |
| Biên khoảng cách và hướng | "≤ 20 m", "≤ 45°" | Cả hai biên đều tính là khớp |
| Biển bị từ chối 30 ngày | "hơn 30 ngày" thì bỏ qua | Mốc là `receivedAt` của đề xuất. Biển còn xét khi `receivedAt − reviewedAt ≤ 30 ngày`; đúng 30 × 24 giờ vẫn xét, thêm 1 ms thì bỏ |
| Từ chối hay biển khác mã | FR-20 nói "không có biển đang hoạt động phù hợp"; `docs/04` xét bước 4 trước bước 5 | Theo `docs/04`: biển `REJECTED` cùng mã trong 30 ngày thắng biển `APPROVED` khác mã. Biển `PENDING` vẫn thắng vì bước 3 đứng trước |
| Đề xuất bị từ chối theo bước 4 | Không nói trọng số | `w = 0`, gán `sign_id` vào biển bị từ chối, biển không đổi, không tăng `version` |
| Chống lặp 12 giờ | "Mỗi người góp trọng số tối đa một lần mỗi 12 giờ cho một biển" | `k = 0` khi cùng người đã có một đề xuất **có trọng số** (`w > 0`) trên biển này với khoảng cách giữa hai `receivedAt` dưới 12 giờ. Dùng cửa sổ hai phía để đúng cả khi đề xuất cũ được xử lý muộn (quét lại, IT-B12). Đúng 12 giờ thì có trọng số lại |
| Đếm người | `distinct_submitters`, `submitter_count` | Đếm mọi người có đề xuất trên biển (hoặc mang mã đó), kể cả lần có `w = 0` |
| Mã dẫn đầu khi hoà | Không nói | `W_c` lớn hơn → `submitter_count` lớn hơn → mã đến trước (đề xuất sớm nhất mang mã đó theo `receivedAt`) → thứ tự từ điển của mã |
| Mã dẫn đầu của biển đã duyệt | "mã kiểm duyệt viên chốt" | Với `APPROVED`, `UNDER_REVIEW`, `REJECTED`, `REMOVED`, `c*` là `signTypeCode` hiện tại, không tính lại |
| `s2` | "tỉ lệ trọng số của mã đứng thứ hai" | `max_{c≠c*} W_c / Σ W_c`; tổng bằng 0 thì `s2 = 0`. So sánh chính xác bằng `BigDecimal`, không làm tròn |
| Làm tròn | "4 chữ số khi lưu" | `w` làm tròn 4 chữ số (`HALF_UP`) trước khi cộng vào `W_c`, để `W_c` bằng tổng `w` đã lưu. Điểm làm tròn 4 chữ số; các ngưỡng 0,8, 0,85, 0,5 so trên điểm đã làm tròn để gợi ý khớp số kiểm duyệt viên thấy |
| Trung vị có trọng số | Không nói cách xử lý điểm giữa | Sắp tăng dần, lấy giá trị đầu tiên có trọng số cộng dồn vượt nửa tổng; nếu cộng dồn đúng bằng nửa tổng thì lấy trung bình giá trị đó và giá trị kế tiếp. Tổng trọng số bằng 0 thì giữ vị trí cũ |
| Hướng của biển | Không nói cách cập nhật | Lấy hướng của đề xuất tạo biển, sau đó không đổi (kiểm duyệt viên sửa ở lát 6) |
| `M` khi tự duyệt | "kiểm duyệt viên đã duyệt hoặc tạo biển" | Tự duyệt không đặt `M`, vì không có bằng chứng mới từ người |
| Lịch sử khi đổi vị trí | FR-23, NFR-11: mọi thay đổi vị trí ghi lịch sử | Người điều phối chốt: khi vị trí trung vị khác vị trí cũ sau khi làm tròn 6 chữ số thập phân, sinh `LOCATION_RECOMPUTED` (`actor_type = SYSTEM`, trước và sau là `{lat, lng}`); lệch nhỏ hơn thì giữ vị trí cũ. Nếu cùng lúc đổi mã dẫn đầu thì ghi hai sự kiện riêng, `LEADING_CODE_CHANGED` trước. Biển mới chỉ có `CREATED`; đánh giá không tính lại vị trí. Đã thêm hành động vào bảng `docs/06 §8` |
| Ngưỡng hoà gần nhất | "gần nhất" | Khoảng cách bằng nhau thì chọn `id` nhỏ hơn theo thứ tự từ điển |
| Rà soát lại khi có xác nhận | FR-22 chỉ nói đánh giá | Luật rà soát áp dụng mỗi khi điểm của biển `APPROVED` được tính lại, kể cả khi có đề xuất xác nhận |
| Đánh giá | Một đánh giá mỗi người, ghi đè | Hàm nhận giá trị cũ, thời điểm cũ và giá trị mới. `deny_since_review` chỉ trừ khi đánh giá "không đúng" cũ có thời điểm không trước `reviewedAt`. Chỉ áp dụng cho biển `APPROVED` và `UNDER_REVIEW` |
| Trạng thái đề xuất | Không nói cho biển `UNDER_REVIEW` | Xác nhận biển `APPROVED` thì `ACCEPTED`; xác nhận biển `UNDER_REVIEW` thì `GROUPED`, lát 6 cập nhật khi giữ hoặc gỡ |

## Phần 1 · Lõi logic thuần

**Tạo trong** `backend/src/main/java/vn/signa/sign/clustering/`: `GeoPoint`, `Geo`, `SubmissionFlag`, `SubmissionStatus`, `SubmissionInput`, `MemberSubmission`, `SignStatus`, `SignFlag`, `Suggestion`, `CodeWeight`, `SignState`, `ClusteringConfig`, `Assignment`, `Weights`, `Scoring`, `Suggestions`, `Location`, `SignEvent`, `SignEventType`, `MemberUpdate`, `RatingValue`, `RatingChange`, `RatingOutcome`, `ClusteringOutcome`, `ClusteringEngine`.

### Nhiệm vụ 1 · Kiểu dữ liệu và hình học

- [x] `GeoPoint(lat, lng)` kiểm tra miền giá trị; `SubmissionInput` gồm `id`, `userId`, `signTypeCode`, `categoryCode`, `confidence`, `location`, `headingDeg` (nullable, 0–359), `flags`, `receivedAt`.
- [x] `SignState` gồm `id`, `status`, `signTypeCode`, `categoryCode`, `location`, `headingDeg`, `locationLocked`, `codeWeights`, `distinctSubmitters`, `confirmCount`, `denyCount`, `denySinceReview`, `moderatorBoost`, `confidence`, `suggestion`, `flags`, `relatedSignId`, `reviewedAt`, `version`, `members`. `REJECTED` bắt buộc có `reviewedAt`.
- [x] `Geo.distanceMeters` (haversine), `Geo.headingDiff` = `min(d, 360 − d)`.

### Nhiệm vụ 2 · Luật gán, trọng số, điểm, gợi ý, vị trí

- [x] `Assignment`: 5 bước của `docs/04 §6`, đúng thứ tự; kết quả `CONFIRM`, `JOIN_PENDING`, `REJECT`, `CREATE` (kèm `DIFFERS_FROM_APPROVED`, `relatedSignId`).
- [x] `Weights`: `w = p × k × f`; `USER_EDITED` thì `p = 0,7`; `SPLIT_VOTE` thì `f = 0,5`; chống lặp 12 giờ.
- [x] `Scoring`: `α`, `β`, điểm, mã dẫn đầu kèm luật hoà, `s2`.
- [x] `Suggestions`: Cần xem kỹ → Nên duyệt → Chờ thêm; tự duyệt (FR-25) theo cấu hình; rà soát lại (FR-22).
- [x] `Location`: trung vị có trọng số trên đề xuất mang mã dẫn đầu; giữ nguyên khi `locationLocked`.

### Nhiệm vụ 3 · `ClusteringEngine`

- [x] `process(SubmissionInput, List<SignState>, Instant now)` → `ClusteringOutcome`: biển đích (mới hoặc đã có, kèm trạng thái mới), `w`, trạng thái đề xuất, lý do từ chối, cập nhật đề xuất thành viên khi tự duyệt, sự kiện lịch sử kèm giá trị trước và sau.
- [x] `applyRating(SignState, RatingChange)` → `RatingOutcome` cho lát 7 (FR-09, FR-22).
- [x] `memberUpdates(SignState)`: `APPROVED` thì cùng mã `ACCEPTED`, khác mã `REJECTED`; `REJECTED` thì mọi đề xuất `REJECTED`. Cho lát 6.

### Nhiệm vụ 4 · Kiểm thử và độ phủ

- [x] UT-B01 → UT-B10, UT-B12 đúng số liệu `docs/08 §2.2`.
- [x] Ví dụ số `docs/04 §6`: một người 0,74; biển đã duyệt nhận 2 "không đúng" 0,63.
- [x] Ca biên: 350° và 10° lệch 20°; thiếu hướng một phía; từ chối ngày 30 và ngày 31; hoà trọng số; đề xuất lặp sau 12 giờ; biên 20 m và 45°.
- [x] Test lặp ngẫu nhiên có seed: điểm luôn trong [0; 1], trọng số không âm.
- [x] `ClusteringPurityTest`: gói không phụ thuộc Spring, JDBC, I/O.
- [x] `LOCATION_RECOMPUTED` khi vị trí trung vị đổi (so sau khi làm tròn 6 chữ số), kèm test.
- [x] `jacocoTestCoverageVerification` ≥ 90% dòng cho `vn.signa.sign.clustering`, khối mới ở cuối `build.gradle.kts`.
- [x] `./gradlew spotlessCheck test compileTestJava jacocoTestCoverageVerification` thành công; markdownlint sạch; commit.

## Phần 2 · Tích hợp (chưa làm)

Cần merge lát 1 (đăng nhập, `submission:create`) và lát 2 (`SignCatalog` để tra `categoryCode`, kiểm `labelMapVersion`).

### Nhiệm vụ 5 · Repository JDBC

- [ ] `SignRepository`, `SignCodeWeightRepository`, `SubmissionRepository` trong `sign.internal`, dùng `JdbcClient` và SQL viết tay, chỉ trên `signs`, `sign_code_weights`, `submissions`.
- [ ] Lọc thô ứng viên bằng `MBRContains` quanh điểm, `category_code` cùng nhóm, `status <> 'REMOVED'`; lọc chính xác do `Assignment` làm. Nửa cạnh theo vĩ độ ±0,0002°; theo kinh độ tính `Δlng = 20 / (111 320 · cos(lat))` độ thay vì ±0,0002° cố định, vì ±0,0002° kinh độ chỉ phủ đủ 20 m khi vĩ độ dưới khoảng 25,8°.
- [ ] Đọc ứng viên bằng `SELECT … FOR UPDATE` trong giao dịch của worker để không ghi đè quyết định duyệt đồng thời; ghi biển bằng `WHERE id = ? AND version = ?`.
- [ ] Nạp đề xuất thành viên của ứng viên (cột `user_id`, `sign_type_code`, `weight`, `location`, `received_at`, `status`).

### Nhiệm vụ 6 · `POST /submissions`

- [ ] Multipart `metadata` + `crop` (≤ 200 KB, JPEG), kiểm `labelMapVersion`, ghi `sign-crops/{newId}.jpg`, `INSERT` `RECEIVED`.
- [ ] Trùng `UNIQUE(user_id, client_id)`: đọc bản ghi thắng, xoá ảnh vừa ghi, trả `200` cùng ID (IT-B01, IT-B14).
- [ ] Commit xong mới `XADD stream:submissions`; Redis lỗi vẫn trả `201`.
- [ ] Thêm dòng vào `PermissionMatrixTest`; cập nhật mục endpoint trong `docs/07`.

### Nhiệm vụ 7 · Worker và vận hành

- [ ] Consumer group `clustering`, `XREADGROUP`; mỗi mục khoá đề xuất `FOR UPDATE`, bỏ qua nếu khác `RECEIVED`, gọi `ClusteringEngine.process`, ghi biển, trọng số, đề xuất, lịch sử (`audit.api`, `actor_type = SYSTEM`) trong một giao dịch, rồi `XACK`, `XDEL`. Bật bằng `signa.sign.clustering.worker.enabled`.
- [ ] Khởi động đọc lại mục chưa `XACK`; quét định kỳ đề xuất `RECEIVED` cũ hơn 1 phút; thử lại có khoảng nghỉ.
- [ ] Ngưỡng `ClusteringConfig` và cờ tự duyệt (mặc định tắt) đọc từ khối `signa.sign:` ở cuối `application.yml`.
- [ ] Job dọn ảnh `sign-crops`: biển `REJECTED` sau 90 ngày; ảnh không có bản ghi MySQL cũ hơn 1 giờ.
- [ ] Metric: độ dài stream, số mục chưa `XACK`, số đề xuất `RECEIVED`, tuổi đề xuất chờ lâu nhất.
- [ ] IT-B01, IT-B02, IT-B03, IT-B12, IT-B14 (chạy trên CI).

### Ghi chú cho lát 6

- Khi kiểm duyệt viên duyệt (hoặc giữ biển `UNDER_REVIEW`): đặt `moderator_boost = 2` (`M`), `reviewed_at` là thời điểm quyết định, `deny_since_review = 0`, tính lại điểm bằng `Scoring.score`, rồi cập nhật đề xuất thành viên bằng `ClusteringEngine.memberUpdates`.
- Biển được duyệt với mã không dẫn đầu vẫn giữ `W_c` của mã khác trong `β`, nên điểm có thể dưới 0,5 và bị rà soát lại ở lần đánh giá hoặc xác nhận kế tiếp. Cần nhóm xem lại nếu điều này gây phiền khi thử nghiệm.

## Tiến độ

Ghi ở đây trạng thái cuối và việc còn dở khi dừng giữa chừng.

**2026-11-23.** Phần 1 xong trên `backend/5-submissions-clustering`, chưa push. Phần 2 chưa bắt đầu, chờ lát 1 và lát 2.

- Trên máy: `./gradlew spotlessApply test compileTestJava jacocoTestCoverageVerification` thành công, 76 unit test đạt. 56 test của gói `clustering` (`ClusteringEngineTest` 35, `ComponentsTest` 10, `RatingTest` 9, `RandomizedInvariantsTest` 1 với 300 lượt × 40 bước, seed 20261123, `ClusteringPurityTest` 1). Độ phủ dòng của gói 98,9% (530/536), nhánh 94% (250/265). Đã thử nâng ngưỡng lên 99,9% để chắc luật JaCoCo chặn được.
- Ánh xạ ca kiểm thử: UT-B01 → UT-B10 và UT-B12 nằm trong `ClusteringEngineTest` (UT-B08 trong `RatingTest`), tên hiển thị bắt đầu bằng mã ca. Các ví dụ số trong `docs/04 §6`: một người 0,7368; biển đã duyệt nhận 2 "không đúng" 0,6324.
- Nhiệm vụ 1–3 gộp một commit vì test dùng chung fixture; nhiệm vụ 4 (JaCoCo, kế hoạch) một commit.
- `jacocoTestCoverageVerification` chưa nằm trong `check` và workflow CI. Thêm vào `backend.yml` là việc của người điều phối, vì file này dùng chung.
- Người điều phối đã đồng ý các luật trong mục "Luật đã chốt khi tài liệu chưa nói rõ" (R = 6 370 986 m, biên bao gồm, bước 4 trước bước 5, luật hoà, so trên điểm đã làm tròn) và yêu cầu thêm `LOCATION_RECOMPUTED`. Còn chờ cả nhóm review: `M` khi tự duyệt, hướng của biển.
- Rủi ro cho lát 6: xem mục "Ghi chú cho lát 6".
