# Lát 10 · Hoàn thiện backend

> Kế hoạch thực thi theo [thiết kế triển khai](../thiet-ke-trien-khai.md)
> và quy tắc làm song song. Đánh dấu `- [x]` khi xong từng bước.

Mục tiêu là triển khai Signa qua một gateway và chuẩn bị kịch bản kiểm thử
hiệu năng cho môi trường tương đương bản demo.

## Phần A · Hạ tầng và kịch bản k6

- [x] Cấu hình nginx định tuyến API, ảnh và web quản trị theo
  [Kiến trúc §8–9](../../03-kien-truc.md#8-triển-khai). Ghi đè
  `X-Forwarded-For` bằng `$remote_addr` và sinh mã request khi thiếu header.
- [x] Thêm dịch vụ nhận diện, gateway và web quản trị vào Compose. Dịch vụ
  nhận diện nằm trong mạng nội bộ, nạp ba checkpoint từ thư mục chỉ đọc
  và báo trạng thái qua `/healthz`.
- [x] Thêm cấu hình demo bật TLS, chỉ mở cổng gateway, đặt chính sách
  khởi động lại và giới hạn bộ nhớ cho máy 4 vCPU, 8 GB RAM. MySQL, Redis
  và MinIO mở cổng trên loopback ở môi trường dev để kiểm thử từ host.
- [x] Thêm ba kịch bản k6 theo [Kiểm thử §8](../../08-kiem-thu.md#8-kiểm-thử-hiệu-năng-server)
  và hướng dẫn chạy. Kiểm thử tải nhận diện gọi API nội bộ để đo cả ba
  mô hình ở tốc độ yêu cầu mà không chạm giới hạn của API công khai.
- [x] Kiểm tra Compose, nginx, kịch bản k6, Gradle và Markdown. Ghi các
  kiểm tra cần môi trường thật vào mục Tiến độ.

Gateway proxy tới `web-admin:3000` để dùng lại luồng build, fallback cho
SPA và cache tài nguyên của web quản trị. Gateway xử lý các đường dẫn
API và ảnh trước khi chuyển phần còn lại tới web quản trị. URL ảnh ký
qua gateway giữ nguyên `Host` khi chuyển tới MinIO để chữ ký còn hiệu lực.

Phạm vi thay đổi là `infra/`, kế hoạch này và các tệp mới trong
`backend/src/test/k6/`. Các lát 2, 8 và 9 có thể tiếp tục độc lập.

## Phần B · Sau khi ghép các lát

- [x] Viết script mô phỏng đề xuất và đánh giá phê duyệt theo
  [Kiểm thử §6](../../08-kiem-thu.md#6-đánh-giá-phê-duyệt-có-trợ-giúp).
- [x] Đối chiếu từng mục [Kiểm thử §7](../../08-kiem-thu.md#7-kiểm-thử-bảo-mật)
  với ca kiểm thử hiện có. Ghi các mục cần chạy trên CI hoặc máy demo.
- [ ] Đo k6 trên môi trường demo với dữ liệu mẫu và checkpoint thật.
  Ghi kết quả, xác nhận hàng đợi đề xuất xử lý hết tải và đối chiếu NFR.

### Mô phỏng phê duyệt

Chạy từ gốc repo với JDK 21:

```sh
bash backend/src/test/shell/approval-simulation.sh
```

Script dùng `ClusteringEngine` với 60 biển chuẩn trên hai tuyến giả lập, ba
lượt trên mỗi tuyến, mỗi lượt một tài khoản, seed `20261124`. Cả hai phương
án nhận cùng một tập đề xuất và nhãn chuẩn cho từng kịch bản. Mỗi đề xuất
hoặc mỗi cụm tương ứng một quyết định. Kiểm duyệt mô phỏng chấp nhận gợi ý
**Nên duyệt** theo mã dẫn đầu và đối chiếu nhãn chuẩn cho các quyết định
còn lại. Biển khớp khi đúng mã, cách biển chuẩn không quá 15 m. Mỗi biển
chuẩn chỉ được tính một lần. Tự duyệt tắt khi so sánh hai phương án và
được đo riêng trong lần chạy FR-25.

| Kịch bản | Quyết định từng đề xuất | Precision từng đề xuất | Quyết định gom cụm | Precision gom cụm | Recall gom cụm | Precision tự duyệt |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| Bình thường | 180 | 100% | 60 | 100% | 100% | 100% |
| Nhiễu mô hình 10% | 180 | 100% | 60 | 100% | 100% | 100% |
| Nhiễu mô hình 20% | 180 | 100% | 60 | 98,33% | 98,33% | 97,06% |
| Nhiễu GPS σ = 5 m | 180 | 100% | 61 | 100% | 100% | 100% |
| Nhiễu GPS σ = 10 m | 180 | 100% | 95 | 84,85% | 93,33% | 96,88% |
| Nhiễu GPS σ = 15 m | 180 | 100% | 132 | 71,67% | 71,67% | 87,50% |
| Một người dùng phá 20% biển | 240 | 100% | 60 | 100% | 100% | 100% |

Kịch bản bình thường giảm 66,67% quyết định. Kịch bản người dùng phá giảm
precision 0 điểm phần trăm so với bình thường. Hai mức nhiễu GPS từ 10 m
không đạt ngưỡng giảm quyết định và precision của G4 trên dữ liệu giả lập.
Kết quả nghiệm thu G4 còn cần dữ liệu từ ba lượt đi trên mỗi tuyến thực địa.

### Checklist bảo mật

| Mục §7 | Bằng chứng hoặc phần chưa có |
| --- | --- |
| API cần đăng nhập trả `401` khi thiếu token, token sai hoặc bị thu hồi | `PermissionMatrixTest.statusFollowsMatrix` kiểm khách trên các route trong ma trận. `AuthIT.missingWrongOrRevokedTokenIs401` kiểm đủ ba tình huống trên `/me`. Chưa có ma trận token sai và token đã thu hồi cho từng route. |
| Quyền trên mọi endpoint quản trị | `PermissionMatrixTest.statusFollowsMatrix` kiểm UT-B11 theo endpoint và vai trò; `matrixMatchesDocs06` đối chiếu bảng quyền. |
| Người dùng chỉ đọc đề xuất, báo cáo của mình | `SubmissionIT.listReturnsOnlyOwnSubmissionsInReceivedOrder` và `FeedbackIT.myReportsOnlyReturnsTheCallersReportsAndPaginates` thử hai người dùng. Các route đọc dành cho người dùng lấy ID từ phiên, ở `/me/submissions` và `/me/reports`. |
| Đầu vào sai, quá dài hoặc toạ độ ngoài phạm vi trả `400`, ảnh giả mạo trả `415` | `AuthControllerTest.registerValidatesFields`, `registerRejectsDisplayNameOver100`, `MapIT.invalidBboxIsValidationError` và `RecognitionIT.textRenamedToJpgIsUnsupported`. |
| Đăng nhập, OTP và nhận diện giới hạn tần suất | `AuthIT.eleventhWrongAttemptIsRateLimited`, `mysqlEquivalentEmailsShareTheLoginLimit`, `PasswordResetIT.fourthSendIsRateLimitedForActiveAndUnknownAddresses`, `RecognitionIT.the31stCallFromOneIpWithinAMinuteIsRateLimited`. |
| FR-13 trả đồng nhất `202`, OTP hết hạn hoặc bị xoá đúng lúc, phiên cũ bị thu hồi | `PasswordResetIT.fourthSendIsRateLimitedForActiveAndUnknownAddresses`, `otpExpiresAfterTenMinutesAccordingToRedisTtl`, `resendReplacesCodeAndFifthWrongAttemptDeletesIt` và `forgotAndResetChangePasswordAndRevokeExistingSessions`. |
| BCrypt, log không chứa mật khẩu hoặc token | `AccountServiceTest.registerStoresBcryptHashAndUserRole`, `PasswordConfigTest.usesBcryptCost12` và `AuthIT.passwordsAndTokensNeverReachTheLog`. |
| Hai bucket riêng tư yêu cầu URL ký | `MinioObjectStorageIT.privateBucketsRejectUnsignedRead` thử `sign-crops` lẫn `report-photos`. |
| Nhận diện, MySQL, Redis không truy cập được từ ngoài máy chủ | Compose demo chỉ publish `443` của nginx. Chưa có phép thử từ máy khác vì môi trường demo chưa chạy. |
| Quét bí mật trên mọi pull request | Job `gitleaks` trong `.github/workflows/backend.yml` chạy với PR chạm backend, infra hoặc workflow này. Chưa có quét cho mọi PR do workflow có bộ lọc đường dẫn. |

## Tiến độ

**2026-11-24.** Kế hoạch ở commit `202de8e`. Phần A hoàn tất trên
`backend/10-hardening`. Phần B chờ tích hợp các lát.

- Compose CLI độc lập đã phân giải cấu hình gốc và các profile `migrate`,
  `app`, `dev`, `demo`. Dev chỉ bind loopback. Override demo chỉ publish
  443, mount TLS và checkpoint chỉ đọc, giới hạn tổng 6,5 GiB cho các
  dịch vụ chạy lâu dài. Recognition dùng PyTorch CPU trên mạng nội bộ.
- `nginx -t` bằng nginx 1.30.8 và thử HTTP/HTTPS với upstream mô phỏng
  đạt. Đã kiểm đường dẫn API, SPA, icon, giới hạn 12 MiB, header truy vết
  và chuỗi ký giữ nguyên khi chuyển sang đường dẫn ảnh riêng tư.
- k6 2.4.0 đọc được cả ba kịch bản. Thử ngắn với API mô phỏng đạt cho
  bbox, đăng nhập và một đề xuất multipart, cùng phản hồi nhận diện
  `200` và `503` có `Retry-After`. Gradle đạt 163 ca và Markdown đã lint.
- Trong môi trường Python 3.11, đã cài phụ thuộc nhận diện CPU, nạp 46
  nhãn, import YOLOv5 ở commit đã ghim và kiểm tra `/healthz` trả `503`
  khi thiếu checkpoint.
- Máy thiếu Docker Engine và checkpoint nhóm. Còn phải build và chạy
  các container cùng nhau, kiểm chữ ký với MinIO thật và xác nhận mức
  RAM khi nạp ba mô hình trên máy demo. Kết quả tải và bảo mật đầy đủ
  thuộc phần B.

**2026-11-24 · Sau khi ghép lát 1–9.** Merge `0608b53` giữ cấu hình OTP,
SMTP và Mailpit của lát 9 cùng gateway và mạng nhận diện của phần A.
Bộ Gradle ban đầu đạt. Đã hoàn thành mô phỏng và đối chiếu checklist §7.

- `./gradlew spotlessCheck test compileTestJava jacocoTestCoverageVerification`
  đạt 701 ca, không có ca lỗi hoặc bị bỏ qua. Markdown lint đạt.
- Kết quả và commit các phát hiện tài khoản, phiên, hạ tầng và CI ở
  [bảng review](../review/README.md).
- Compose CLI độc lập đã kiểm cấu hình đã phân giải cho `app`, `dev` và
  `demo`, gồm URL nhận diện, mạng nội bộ, tên tài khoản MySQL tuỳ chỉnh và
  cổng gateway. Test shell A4 đạt.
- `CredentialVersionIT`, `AuthIT`, `DemoMigrationIT`, `SchemaIT` và
  `BootstrapAdminIT` đã biên dịch. Chạy các ca này cần Docker Engine của CI.
- Chờ ghép phần review fix2, dữ liệu thực địa cho G4 và phép đo k6 trên
  máy demo với checkpoint thật.
