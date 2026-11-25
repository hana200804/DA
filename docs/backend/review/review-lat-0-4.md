# Review backend Signa lát 0–4

Đã đối chiếu từng cặp nhánh liên tiếp từ `hana200804/Backend` tới `backend/4-map` và quy phát hiện cho lát đưa lỗi vào. Kết luận dựa trên mã nguồn, cấu hình và test đã đọc. Chưa chạy Gradle, Docker hay `*IT`. Đường dẫn và dòng theo nhánh chứa đoạn mã được dẫn.

| Mức độ | Số phát hiện |
| --- | ---: |
| S1 | 2 |
| S2 | 6 |
| S3 | 7 |
| S4 | 0 |
| **Tổng** | **15** |

## S1

### 1. Mật khẩu cũ có thể tạo phiên còn hiệu lực sau khi đổi mật khẩu

- **Lát** 1
- **Vị trí** `backend/src/main/java/vn/signa/identity/internal/AccountService.java:85`, `backend/src/main/java/vn/signa/identity/internal/AccountService.java:134`, `backend/src/main/java/vn/signa/identity/internal/RedisSessionStore.java:103`, `backend/src/main/java/vn/signa/identity/internal/SessionService.java:37`
- **Lỗi và kịch bản** Một yêu cầu đăng nhập đọc hash cũ. Thiết bị đang giữ phiên hợp lệ đổi mật khẩu, ghi hash mới, xoá các phiên đã có và nhận `204`. Nếu yêu cầu đăng nhập đầu tiên cấp token sau bước xoá, token đó chưa có mặt lúc Redis lấy danh sách phiên. Token vẫn gọi được `GET /me` và các API theo quyền của tài khoản. Nếu Redis lỗi sau khi MySQL ghi hash mới, thao tác đổi trả lỗi nhưng một số phiên cũ có thể vẫn hợp lệ khi Redis hồi phục. `AuthIT.java:96` chỉ kiểm tra thu hồi tuần tự sau một lần đổi thành công.
- **Cách sửa đề xuất** Gắn phiên với phiên bản thông tin xác thực. Cập nhật hash và tăng phiên bản có điều kiện trong cùng giao dịch MySQL. Khi xác thực token, từ chối phiên mang phiên bản cũ dựa trên trạng thái có thẩm quyền. Giữ phiên hiện tại bằng cách chuyển phiên đó sang phiên bản mới theo FR-01. Thêm IT điều phối thứ tự cấp phiên và kiểm tra lỗi Redis sau commit.
- **Độ chắc chắn** chắc chắn.

### 2. Tải ảnh minh hoạ cùng định dạng ghi đè ảnh cũ kể cả khi giao dịch rollback

- **Lát** 2
- **Vị trí** `backend/src/main/java/vn/signa/catalog/internal/CatalogAdminService.java:165`, `backend/src/main/java/vn/signa/catalog/internal/CatalogAdminService.java:217`, `backend/src/main/java/vn/signa/catalog/internal/CatalogAdminService.java:232`
- **Lỗi và kịch bản** Loại `P.102` đã có ảnh PNG. Khi tải một PNG khác, `storage.put` thay nội dung ở cùng khoá `P.102.png` trước khi ghi MySQL và audit. Vì khoá mới trùng khoá cũ, hàm dọn ảnh bỏ qua đăng ký xử lý rollback. Nếu ghi audit hoặc commit thất bại, API báo lỗi và bản ghi CSDL trở về trạng thái cũ, nhưng URL cũ vẫn mở ảnh mới. Với bucket mặc định, nội dung ảnh cũ đã mất. Khi commit thành công, audit của lần thay ảnh cùng định dạng cũng ghi cùng một khoá trước và sau.
- **Cách sửa đề xuất** Tải ảnh mới lên khoá riêng cho từng phiên bản. Trong giao dịch ngắn, khoá dòng, cập nhật khoá ảnh và ghi audit. Dọn ảnh mới khi rollback và dọn ảnh cũ sau commit, có bước kiểm tra khoá đang được tham chiếu và cơ chế thử lại khi lưu trữ lỗi.
- **Độ chắc chắn** chắc chắn.

## S2

### 3. Khung nhìn chạm kinh độ −180° có thể trả HTTP 500

- **Lát** 4
- **Vị trí** `backend/src/main/java/vn/signa/sign/internal/map/MapQueryParser.java:75`, `backend/src/main/java/vn/signa/sign/internal/map/BoundingBox.java:21`, `backend/src/main/java/vn/signa/sign/internal/map/MapSignRepository.java:108`, `backend/src/test/java/vn/signa/sign/map/MapIT.java:158`
- **Lỗi và kịch bản** `bbox=-180,-85,180,85&zoom=0` qua kiểm tra Java. WKT vẫn có đỉnh mang kinh độ `-180`. Miền kinh độ địa lý của MySQL 8.4 là `(-180, 180]`. `ST_GeomFromText` có thể từ chối WKT ở biên này và lỗi SQL đi tới handler chung, trả `500` cho khung nhìn hợp lệ. `worldWideBboxStillMatches` dùng đúng khung nhìn này và đợi `200` cùng biển ở Hà Nội.
- **Cách sửa đề xuất** Xử lý riêng vùng phủ toàn bộ kinh độ và vùng chạm kinh tuyến đổi ngày. Dùng vùng con hợp lệ để lọc thô khi cần, rồi so `ST_Latitude` và `ST_Longitude` với biên số để cả hai chế độ dùng cùng tập kết quả. Giữ ca IT tại zoom 0 và thêm ca chạm biên tây chỉ phủ một phần kinh độ.
- **Độ chắc chắn** chắc chắn với lệch miền giữa API và MySQL, chưa xác nhận kết quả câu SQL trên container.

### 4. Khởi tạo MySQL có thể hỏng với mật khẩu hợp lệ hoặc tên tài khoản đã cấu hình

- **Lát** 0 cho script khởi tạo, 1 cho cấu hình Compose.
- **Vị trí** `infra/mysql/init/01-users.sh:10`, `infra/mysql/init/01-users.sh:12`, `infra/compose.yaml:19`, `infra/compose.yaml:72`, `infra/compose.yaml:86`
- **Lỗi và kịch bản** Trên volume mới, mật khẩu app hoặc migrator chứa dấu nháy đơn đi thẳng vào SQL literal và làm lệnh `CREATE USER` sai cú pháp. Tiến trình migrate sau đó thiếu tài khoản hoặc quyền cần thiết. Cùng ở bước cấp tài khoản, nếu đổi `SIGNA_DB_MIGRATOR_USER` trong cấu hình Compose, Compose dùng tên mới để kết nối còn script vẫn chỉ tạo `signa_migrator`. Các test dùng tên cố định và mật khẩu mẫu không có ký tự đặc biệt.
- **Cách sửa đề xuất** Dùng cùng cấu hình tên tài khoản ở bước cấp tài khoản và bước kết nối. Truyền mật khẩu qua cơ chế tạo tài khoản có mã hoá literal an toàn theo MySQL. Kiểm tra bootstrap với volume mới, mật khẩu có dấu nháy và tên tài khoản khác mặc định nếu vẫn hỗ trợ tuỳ biến.
- **Độ chắc chắn** chắc chắn.

### 5. Backend trong Compose không nhận địa chỉ dịch vụ nhận diện

- **Lát** 3, trên Compose của lát 1.
- **Vị trí** `infra/compose.yaml:80`, `backend/src/main/resources/application.yml:69`, `backend/src/main/java/vn/signa/recognition/internal/RecognitionClient.java:60`
- **Lỗi và kịch bản** Chạy backend bằng profile Compose `app` và dịch vụ AI ở container khác. Kể cả khi đặt `SIGNA_RECOGNITION_URL` trong `infra/.env`, service `backend` không chuyển biến đó vào container. Backend gọi `http://localhost:8000` của chính container backend. `GET /recognition/models` và `POST /recognition/detect` đều trả `503` trong cấu hình này. `RecognitionIT` chèn URL WireMock thẳng vào Spring nên không kiểm đường cấu hình qua Compose.
- **Cách sửa đề xuất** Truyền `SIGNA_RECOGNITION_URL` vào service backend và xác định tên DNS hoặc địa chỉ AI truy cập được từ mạng Docker. Kiểm tra khói bằng một lượt gọi danh sách mô hình từ cấu hình Compose.
- **Độ chắc chắn** chắc chắn.

### 6. Giới hạn ảnh theo request không giới hạn tổng bộ nhớ giải mã đồng thời

- **Lát** 3
- **Vị trí** `backend/src/main/java/vn/signa/recognition/internal/ImageProcessor.java:35`, `backend/src/main/java/vn/signa/recognition/internal/ImageProcessor.java:62`, `backend/src/main/java/vn/signa/recognition/internal/RecognitionService.java:62`, `backend/src/main/resources/application.yml:18`
- **Lỗi và kịch bản** Java giữ multipart trong RAM, rồi giải mã toàn bộ raster bằng `reader.read(0)` trước khi thu nhỏ. Giới hạn theo phút không giới hạn số raster sống cùng lúc. Với heap 512 MiB, ba yêu cầu đồng thời đều nằm dưới giới hạn byte và có ảnh PNG RGBA 16-bit ở mức 25 triệu pixel có thể cần khoảng 600 MB chỉ cho ba raster gốc khi các lần giải mã chồng nhau. Request có thể hết heap trước khi tới hàng đợi của dịch vụ AI.
- **Cách sửa đề xuất** Giới hạn đồng thời dựa trên chi phí giải mã và giới hạn tổng upload đang giữ trong bộ nhớ. Dùng đọc giảm mẫu ngay từ decoder khi codec hỗ trợ. Trả lỗi quá tải có kiểm soát trước cấp phát lớn và kiểm tra tải với heap giới hạn.
- **Độ chắc chắn** chắc chắn với đường cấp phát và việc thiếu giới hạn tổng, chưa đo mức tải gây lỗi trên máy triển khai.

### 7. Một tài khoản MySQL có thể bị tính vào nhiều bộ đếm đăng nhập

- **Lát** 1
- **Vị trí** `backend/src/main/java/vn/signa/identity/internal/AccountService.java:82`, `backend/src/main/java/vn/signa/identity/internal/LoginRateLimiter.java:39`, `backend/src/main/java/vn/signa/identity/internal/UserRepository.java:23`, `infra/mysql/init/01-users.sh:11`
- **Lỗi và kịch bản** Java chỉ trim và chuyển email thành chữ thường. Cột `users.email` kế thừa `utf8mb4_0900_ai_ci`, nên MySQL có thể khớp hai chuỗi khác nhau về dấu với cùng một tài khoản. Sau khi email gốc chạm ngưỡng tại một IP, biến thể tương đương theo collation vẫn được đối chiếu mật khẩu với cùng dòng người dùng dưới một khoá Redis khác. Giới hạn theo tài khoản và IP vì vậy không thực thi nhất quán.
- **Cách sửa đề xuất** Ràng buộc khoá cho tài khoản tồn tại theo ID ổn định và IP, hoặc dùng cùng biểu diễn email chuẩn hoá cho cả khoá và cột so sánh. Giữ bộ đếm cho email chưa tồn tại và phản hồi chung. Thêm IT MySQL/Redis cho hai chuỗi được CSDL coi là cùng email.
- **Độ chắc chắn** chắc chắn.

### 8. SchemaIT đợi migration 3 khi lát 2 đã thêm V20

- **Lát** 2, hồi quy ở test khung lát 0.
- **Vị trí** `backend/src/test/java/vn/signa/db/SchemaIT.java:33`, `backend/src/main/resources/db/migration/V20__sign_types_label_name.sql:5`, `backend/src/test/java/vn/signa/support/Containers.java:65`, `.github/workflows/backend.yml:39`
- **Lỗi và kịch bản** Với MySQL mới và context test migrate thành công, `info.current()` báo V20, trong khi test buộc bằng `"3"`. CI sẽ đỏ khi đến assertion này trên các nhánh lát 2–4, kể cả khi toàn bộ migration đã chạy đúng.
- **Cách sửa đề xuất** Xác nhận migration bắt buộc đã áp dụng, danh sách pending rỗng và cột do từng migration tạo có mặt. Bỏ điều kiện V3 phải là migration cuối.
- **Độ chắc chắn** chắc chắn.

## S3

### 9. Chạy migration thường lại trên CSDL đã nạp demo bị lỗi Flyway validate

- **Lát** 2
- **Vị trí** `backend/src/main/resources/application-demo.yml:4`, `backend/src/main/resources/db/demo/R__demo_data.sql:1`, `backend/README.md:74`, `backend/README.md:112`, `backend/src/test/java/vn/signa/catalog/DemoDataIT.java:39`
- **Lỗi và kịch bản** Dùng `migrate,demo` nạp dữ liệu mẫu trên volume dev, rồi lần nâng schema sau dùng lệnh `migrate` thường trên cùng volume. Lịch sử Flyway vẫn chứa repeatable migration demo nhưng resolver của profile thường không quét thư mục `db/demo`. Flyway báo migration đã áp dụng nhưng thiếu tệp và chặn migrate. Test chạy SQL trực tiếp qua `ResourceDatabasePopulator` để tránh tạo dòng lịch sử đó, nên không kiểm được vòng đời hai profile.
- **Cách sửa đề xuất** Tách bước nạp dữ liệu demo khỏi lịch sử migration schema, hoặc dùng CSDL và lịch sử riêng cho demo. Thêm IT chạy lại migration schema trên volume đã nạp demo theo luồng được hỗ trợ và xử lý chuyển tiếp cho volume dùng cấu hình cũ.
- **Độ chắc chắn** chắc chắn.

### 10. Lượt đọc đang chạy có thể ghi lại cache danh mục cũ sau khi quản trị viên commit

- **Lát** 2
- **Vị trí** `backend/src/main/java/vn/signa/catalog/internal/CatalogQueryService.java:36`, `backend/src/main/java/vn/signa/catalog/internal/SignTypesCache.java:40`, `backend/src/main/java/vn/signa/catalog/internal/SignTypesCache.java:58`
- **Lỗi và kịch bản** Khi cache trống, GET đọc danh sách còn chứa `P.104`. Quản trị viên ngừng dùng loại này, commit và xoá khoá Redis trước khi GET kịp ghi cache. Sau đó GET ghi body cũ vào đúng khoá với TTL 24 giờ. Các lượt GET danh sách sau tiếp tục thấy `P.104` hoặc nhận `304` với ETag cũ, trong khi GET chi tiết trả `404`.
- **Cách sửa đề xuất** Gắn cache với phiên bản danh mục đổi cùng giao dịch ghi, hoặc chỉ công bố kết quả cache nếu phiên bản đọc ban đầu vẫn khớp khi ghi. Thêm test buộc lượt đọc MySQL kết thúc trước commit và lượt ghi Redis xảy ra sau xoá cache.
- **Độ chắc chắn** chắc chắn.

### 11. Nạp nhãn có thể ghi đè tên vừa sửa và ghi sai giá trị trước thay đổi

- **Lát** 2
- **Vị trí** `backend/src/main/java/vn/signa/catalog/internal/LabelImporter.java:108`, `backend/src/main/java/vn/signa/catalog/internal/LabelImporter.java:137`, `backend/src/main/java/vn/signa/catalog/internal/LabelImporter.java:162`, `backend/src/main/java/vn/signa/catalog/internal/CatalogRepository.java:123`, `backend/src/main/java/vn/signa/catalog/internal/CatalogRepository.java:153`
- **Lỗi và kịch bản** Lệnh nạp file v2 đọc loại `P.102` khi `name` còn bằng `label_name`. Một instance đang phục vụ nhận PATCH đổi tên và commit. Lệnh nạp dùng bản chụp cũ để chọn tên mới từ YAML và ghi vô điều kiện theo mã. Tên quản trị viên vừa lưu bị mất, còn dòng audit của bước nạp dùng tên trước PATCH làm giá trị trước thay đổi. Test giữ tên sửa chỉ thực hiện lần lượt.
- **Cách sửa đề xuất** Khoá và đọc lại dòng ngay trước khi quyết định trường nào được nạp, hoặc kiểm tra phiên bản khi update và đọc lại khi có xung đột. Dựng dữ liệu audit từ dòng thực tế đã khoá.
- **Độ chắc chắn** chắc chắn.

### 12. Đổi nhóm một loại biển để lại nhóm cũ trên bản đồ

- **Lát** 2 và 4
- **Vị trí** `backend/src/main/java/vn/signa/catalog/internal/CatalogAdminService.java:139`, `backend/src/main/java/vn/signa/catalog/internal/CatalogRepository.java:166`, `backend/src/main/java/vn/signa/sign/internal/map/MapSignRepository.java:108`, `docs/06-co-so-du-lieu.md:217`
- **Lỗi và kịch bản** Một biển đã duyệt có `category_code` sao chép từ loại. Sau khi quản trị viên PATCH chuyển loại sang nhóm khác, chỉ `sign_types.category_code` đổi. Danh mục trả nhóm mới nhưng GET bản đồ vẫn lọc và trả bằng `signs.category_code` cũ. Biển biến mất khi lọc nhóm mới và còn xuất hiện khi lọc nhóm cũ.
- **Cách sửa đề xuất** Qua API nội bộ của module `sign`, đồng bộ bản sao nhóm của biển khi đổi nhóm loại, trong cùng giao dịch. Kiểm tra cả đường nạp nhãn có thể đổi nhóm và thêm IT đổi nhóm một loại đang có biển công khai.
- **Độ chắc chắn** chắc chắn.

### 13. API tạo được mã loại không thể dùng trong API chi tiết, sửa hoặc lọc

- **Lát** 2 và 4
- **Vị trí** `backend/src/main/java/vn/signa/catalog/internal/CatalogAdminRequests.java:51`, `backend/src/main/java/vn/signa/catalog/web/CatalogAdminController.java:74`, `backend/src/main/java/vn/signa/catalog/web/CatalogController.java:63`, `backend/src/main/java/vn/signa/sign/internal/map/MapQueryParser.java:107`
- **Lỗi và kịch bản** POST loại chỉ kiểm tra độ dài và khoảng trắng của mã. Mã `TEST/A` có thể được tạo với `201` nhưng dấu gạch chéo chia URL thành hai đoạn, còn đường dẫn mã hoá dấu này bị cấu hình mặc định từ chối. Các endpoint dùng `{code}` không thể xử lý mã theo cách thông thường. Mã `TEST,A` cũng tạo được nhưng khi lọc bằng `signTypeCodes` bị tách thành hai mã riêng sau khi đọc tham số.
- **Cách sửa đề xuất** Dùng một quy tắc mã chung cho tạo, nạp nhãn và các API tiêu thụ mã. Cho phép đầy đủ ký tự dùng trong các mã `labels.yaml` hiện có và chặn ký tự làm thay đổi đoạn đường dẫn hoặc cú pháp danh sách. Rà soát các mã đã tạo trước khi áp dụng ràng buộc.
- **Độ chắc chắn** chắc chắn.

### 14. Tệp chỉ có chữ ký ảnh vẫn được lưu công khai như ảnh hợp lệ

- **Lát** 2
- **Vị trí** `backend/src/main/java/vn/signa/catalog/internal/CatalogIcon.java:25`, `backend/src/main/java/vn/signa/catalog/internal/CatalogAdminService.java:165`, `backend/src/test/java/vn/signa/catalog/internal/CatalogIconTest.java:15`
- **Lỗi và kịch bản** Tệp chỉ gồm chữ ký PNG, thiếu `IHDR` và dữ liệu điểm ảnh, vẫn được nhận dạng là PNG. Với quyền quản trị, PUT tệp đó đủ nhỏ trả `204`, lưu đối tượng công khai và cập nhật URL. Trình duyệt không thể hiển thị ảnh, kể cả khi trước đó loại biển có ảnh minh hoạ hợp lệ. Unit test hiện xác nhận nhận dạng chính đầu vào chỉ có chữ ký.
- **Cách sửa đề xuất** Kiểm tra ảnh giải mã được với giới hạn kích thước raster, rồi lưu ảnh đã chuẩn hoá hoặc lưu tệp gốc đã qua xác thực. Thêm ca upload ảnh bị cắt sau magic bytes và kiểm ảnh cũ vẫn mở được.
- **Độ chắc chắn** chắc chắn.

### 15. Thu nhỏ có thể làm ảnh hợp lệ ở đầu vào thành ảnh bị dịch vụ AI từ chối

- **Lát** 3
- **Vị trí** `backend/src/main/java/vn/signa/recognition/internal/ImageProcessor.java:59`, `backend/src/main/java/vn/signa/recognition/internal/RecognitionClient.java:127`, `ai/service/images.py:18`, `ai/service/images.py:70`
- **Lỗi và kịch bản** Một ảnh PNG 4096×20 px hợp lệ dưới giới hạn byte và pixel có cả hai cạnh vượt ngưỡng tối thiểu 8 px của dịch vụ AI. Backend thu còn 1280×6 px. Dịch vụ nhận diện từ chối ảnh đã thu vì một cạnh dưới 8 px, nên backend trả `400` cho ảnh mà dịch vụ có thể đọc ở kích thước ban đầu.
- **Cách sửa đề xuất** Giữ điều kiện cạnh tối thiểu khi chuẩn bị ảnh. Nếu phải giữ cạnh dài 1280 px, đệm phần thiếu và trừ phần đệm khi quy đổi box về ảnh gốc. Thêm ca ảnh ngang và ảnh dọc có tỷ lệ cạnh lớn.
- **Độ chắc chắn** chắc chắn.
