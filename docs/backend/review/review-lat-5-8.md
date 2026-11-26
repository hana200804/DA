# Review backend Signa · Lát 5–8

Đối chiếu bốn diff riêng theo thứ tự nhánh `backend/4-map` đến `backend/8-audit-users`. Số dòng theo cây `backend/8-audit-users` tại `db54a12`. Kết luận dựa trên Git, mã nguồn, lược đồ, cấu hình và fixture. Chưa chạy Gradle hoặc các ca `*IT`.

**10 phát hiện trong phạm vi** · S1 **1** · S2 **3** · S3 **6** · S4 **0**

## S1 · Đăng nhập đồng thời có thể cấp lại phiên với quyền đã bị thu hồi

- **Lát** 8.
- **Vị trí** `backend/src/main/java/vn/signa/identity/internal/UserAdminService.java:62`, `backend/src/main/java/vn/signa/identity/internal/AccountService.java:85`, `backend/src/main/java/vn/signa/identity/web/SessionAuthenticationFilter.java:71`.
- **Lỗi** Đăng nhập lấy bản ghi người dùng trước khi xác minh BCrypt và cấp phiên từ vai trò, trạng thái đã đọc. Hai lần thu hồi chỉ xoá các phiên Redis đã tồn tại tại lúc quét. Bộ lọc lấy quyền từ vai trò lưu trong phiên.
- **Kịch bản** Một lượt đăng nhập đọc tài khoản `ADMIN/ACTIVE` rồi còn đang xác minh mật khẩu. Quản trị viên khác hoàn tất hạ vai trò xuống `USER` hoặc khoá tài khoản và cả hai lần thu hồi đều thành công. Lượt đăng nhập tiếp tục từ bản ghi cũ và tạo phiên sau lần thu hồi cuối. Request tiếp theo vẫn được cấp quyền `user:manage` từ phiên `ADMIN`. FR-36 yêu cầu phiên mất hiệu lực khi khoá hoặc đổi vai trò.
- **Cách sửa** Tăng phiên bản xác thực của tài khoản trong cùng giao dịch đổi vai trò hoặc trạng thái. Lưu phiên bản đó khi cấp phiên, kiểm tra phiên bản và trạng thái khi xác thực, rồi dùng việc xoá Redis để dọn phiên. Thêm IT điều phối hai luồng để kiểm tra một lượt đăng nhập đã đọc bản ghi cũ trước khi PATCH commit.
- **Độ chắc chắn** chắc chắn. `AdminUsersIT:37` và `:64` chỉ kiểm tra phiên đã được cấp xong trước PATCH.

## S2 · Quét phục hồi nhân bản hàng đợi khi tồn nhiều đề xuất

- **Lát** 5.
- **Vị trí** `backend/src/main/java/vn/signa/sign/internal/ClusteringWorker.java:83`, `backend/src/main/java/vn/signa/sign/internal/ClusteringWorker.java:114`, `backend/src/main/java/vn/signa/sign/internal/SubmissionStream.java:43`.
- **Lỗi** Mỗi lần quét, worker `XADD` lại toàn bộ đề xuất `RECEIVED` quá một phút, kể cả khi stream đã chứa đề xuất đó. Cùng một luồng phải quét hết mọi lô rồi mới đọc và xử lý mục trong stream.
- **Kịch bản** Redis khôi phục sau khi MySQL đã nhận 100.000 đề xuất. Lần quét đầu thêm 100.000 mục. Nếu xử lý 50 mục/giây, lần quét kế tiếp sau một phút lại thêm khoảng 97.000 mục đang có trong stream. Các vòng sau tiếp tục lặp. Khoá và kiểm tra trạng thái bảo vệ trọng số, nhưng từng mục trùng vẫn phải đọc từ Redis, khoá dòng MySQL và xác nhận. Đề xuất mới xếp sau các bản sao và đi vào lần quét kế tiếp nếu chờ quá một phút.
- **Cách sửa** Dùng một thao tác Redis nguyên tử để đăng hàng theo ID đề xuất tối đa một lần khi mục còn trong stream, đồng thời xoá dấu khi `XACK` và `XDEL`. Giữ khả năng khôi phục từ MySQL nếu Redis mất dữ liệu. Giới hạn lượng quét mỗi vòng để worker vẫn đọc được stream trong lúc phục hồi. Thêm biến thể IT-B12 có nhiều lô và quét lặp khi còn tồn.
- **Độ chắc chắn** chắc chắn. Số liệu minh hoạ theo tốc độ đã nêu, chưa đo tải.

## S2 · MinIO chậm giữ khoá biển và chiếm kết nối MySQL khi gửi báo cáo

- **Lát** 7.
- **Vị trí** `backend/src/main/java/vn/signa/sign/internal/feedback/ReportService.java:68`, `backend/src/main/java/vn/signa/sign/internal/feedback/ReportService.java:84`, `backend/src/main/java/vn/signa/sign/internal/feedback/ReportRepository.java:38`.
- **Lỗi** `create` giữ khoá `FOR SHARE` của biển trong lúc gọi MinIO đồng bộ, trước khi chèn hoặc phát hiện báo cáo trùng. Đánh giá, sửa, gỡ và worker đều cần khoá ghi trên dòng biển đó.
- **Kịch bản** Một người gửi báo cáo có ảnh cho biển `APPROVED` trong lúc MinIO đáp ứng chậm. Yêu cầu đánh giá hoặc gỡ cùng biển phải chờ MinIO dù chỉ cần MySQL. Nhiều upload chậm trên các biển khác nhau còn có thể chiếm hết các kết nối trong pool. Báo cáo trùng cũng đi qua bước upload trước khi trả `409`.
- **Cách sửa** Kiểm tra sớm và tải ảnh với khoá duy nhất bên ngoài giao dịch. Sau đó mở giao dịch ngắn để kiểm lại trạng thái biển dưới khoá và `INSERT` với ràng buộc duy nhất hiện có. Dọn ảnh nếu xác nhận giao dịch đã thất bại và dùng job đối chiếu cho trường hợp kết quả commit chưa rõ.
- **Độ chắc chắn** chắc chắn.

## S2 · Đổi nhóm loại biển làm worker bỏ qua biển cùng mã đã công bố

- **Lát** 5.
- **Vị trí** `backend/src/main/java/vn/signa/sign/internal/ClusteringProcessor.java:56`, `backend/src/main/java/vn/signa/sign/internal/SignRepository.java:33`, `backend/src/main/java/vn/signa/catalog/internal/CatalogAdminService.java:139`.
- **Lỗi** Worker lấy nhóm hiện tại từ danh mục cho đề xuất, nhưng dùng nhóm đã lưu trong `signs` để chọn ứng viên. API danh mục cập nhật nhóm của loại biển trong `sign_types`, còn các biển đã tạo giữ nhóm cũ.
- **Kịch bản** Một biển `APPROVED` mã `P.102` đang ở nhóm A. Quản trị viên chuyển loại `P.102` sang nhóm B. Đề xuất `P.102` mới tại cùng tọa độ và hướng dùng nhóm B, loại biển hiện có ra khỏi tập ứng viên và tạo một biển `PENDING` mới. Hai bản ghi có thể cùng công bố sau khi duyệt. FR-20 yêu cầu xác nhận biển `APPROVED` cùng mã ở gần.
- **Cách sửa** Dùng nhóm hiện hành của loại biển cho cả hai phía hoặc cập nhật nhóm của các biển bị ảnh hưởng trong cùng giao dịch đổi danh mục, qua API nội bộ giữa module. Có bước hiệu chỉnh cho dữ liệu cũ và IT đổi nhóm rồi gửi cùng mã.
- **Độ chắc chắn** chắc chắn.

## S3 · Chốt mã khác vẫn công bố ảnh đại diện của mã cũ

- **Lát** 6, ảnh hưởng luồng chấp nhận báo cáo lát 7.
- **Vị trí** `backend/src/main/java/vn/signa/sign/internal/review/ReviewSignRepository.java:70`, `backend/src/main/java/vn/signa/sign/internal/review/ReviewService.java:108`, `backend/src/main/java/vn/signa/sign/internal/map/MapService.java:57`.
- **Lỗi** `approve`, `keep`, `PATCH` và chấp nhận báo cáo bằng `EDIT` có thể đổi `sign_type_code` và trạng thái đề xuất mà giữ nguyên `cover_crop_key`. Chi tiết công khai ký đúng khoá cũ.
- **Kịch bản** Cụm có hai ảnh mã A và một ảnh mã B. Ảnh đại diện đang là ảnh A. Kiểm duyệt viên xác nhận B, các đề xuất A bị từ chối, nhưng chi tiết biển B tiếp tục trả ảnh A cho tới khi worker ghi lại biển. Bài `ReviewIT:165` dựng tình huống này và kiểm mã, điểm, trạng thái nhưng bỏ qua khoá ảnh đại diện.
- **Cách sửa** Khi mã đổi, chọn trong cùng giao dịch ảnh của đề xuất phù hợp mã mới theo tiêu chí chọn ảnh hiện hành. Xoá khoá đại diện nếu mã mới chưa có ảnh. Kiểm cả khoá lưu trong DB và URL chi tiết sau khi duyệt.
- **Độ chắc chắn** chắc chắn.

## S3 · Trung vị kinh độ có thể kéo biển qua nửa vòng Trái Đất

- **Lát** 5.
- **Vị trí** `backend/src/main/java/vn/signa/sign/clustering/Location.java:28`, `backend/src/main/java/vn/signa/sign/clustering/Location.java:50`.
- **Lỗi** Kinh độ được sắp và lấy trung vị trên trục số tuyến tính. Trường hợp hai trọng số bằng nhau ở hai phía kinh tuyến đổi ngày lấy trung bình về gần kinh tuyến 0°.
- **Kịch bản** Lõi gom hai đề xuất cùng mã, cùng hướng, cùng trọng số tại `(0, 179.99995)` và `(0, -179.99995)`. Hai điểm cách nhau khoảng 11 m. Khi cùng cụm, trung vị trả `(0, 0)`. Biển sai vị trí và đề xuất sau ở tọa độ thật có thể sinh cụm thứ hai. Đây là trường hợp cụ thể của rủi ro đã nêu trong tiến độ lát 5.
- **Cách sửa** Mở vòng kinh độ quanh kinh độ của biển trước khi lấy trung vị có trọng số, sau đó chuẩn hoá kết quả về miền WGS84. Bổ sung ca bằng trọng số qua kinh tuyến đổi ngày và ca nhận đề xuất thứ ba tại chỗ.
- **Độ chắc chắn** chắc chắn ở lõi Java. Bước tìm ứng viên qua kinh tuyến chưa chạy trên MySQL.

## S3 · Dữ liệu chỉ có chữ ký tệp được lưu và trả như ảnh

- **Lát** 5 và 7.
- **Vị trí** `backend/src/main/java/vn/signa/sign/internal/SubmissionService.java:109`, `backend/src/main/java/vn/signa/sign/internal/feedback/ReportPhoto.java:27`.
- **Lỗi** Kiểm tra chỉ xét tiền tố. Upload có chữ ký khớp nhưng thiếu toàn bộ nội dung ảnh vẫn được lưu với MIME ảnh. FR-04 và FR-10 yêu cầu đính kèm ảnh, và hợp đồng trả `415` cho ảnh sai định dạng.
- **Kịch bản** Part `crop` chỉ gồm ba byte nhận dạng JPEG hoặc part `photo` chỉ gồm chữ ký PNG, kèm metadata hợp lệ. API trả `201`, nhưng ảnh đã lưu không hiển thị được khi kiểm duyệt viên mở URL.
- **Cách sửa** Kiểm tra cấu trúc và khả năng giải mã của định dạng sau khi kiểm tra kích thước và chữ ký. Giới hạn kích thước ảnh theo chiều và tổng pixel trước bước giải mã. Trả `415` khi ảnh thiếu dữ liệu hoặc hỏng, trước khi ghi MinIO.
- **Độ chắc chắn** chắc chắn.

## S3 · Lịch sử rà soát do đánh giá ghi sai điểm trước thay đổi

- **Lát** 7. Đường ghi được gọi từ lát 7, hàm xây sự kiện nằm trong lõi lát 5.
- **Vị trí** `backend/src/main/java/vn/signa/sign/clustering/ClusteringEngine.java:167`, `backend/src/main/java/vn/signa/sign/clustering/ClusteringEngine.java:273`, `backend/src/main/java/vn/signa/sign/internal/feedback/RatingRepository.java:25`.
- **Lỗi** Sự kiện `MARKED_UNDER_REVIEW` dùng điểm vừa tính cho cả `before` và `after`. Repository đánh giá cũng chưa nạp `confidence` đã lưu khi khoá biển.
- **Kịch bản** Biển `APPROVED` có `W = 4`, `M = 2`, hai đánh giá DENY sau duyệt và điểm `0.7222`. DENY thứ ba làm điểm xuống `0.6500` và biển vào rà soát. Lịch sử ghi `beforeValue.confidence = 0.6500`, giống `afterValue`, nên không phản ánh giá trị trước theo FR-23.
- **Cách sửa** Nạp `signs.confidence` khi khoá biển và truyền trạng thái trước thay đổi cùng trạng thái đã tính điểm để lập sự kiện. Kiểm cả hai giá trị trong `FeedbackIT:78`, ngoài tên hành động.
- **Độ chắc chắn** chắc chắn.

## S3 · Chi tiết và phân trang phê duyệt có thể ghép các phiên bản dữ liệu

- **Lát** 6.
- **Vị trí** `backend/src/main/java/vn/signa/sign/internal/review/ReviewQueryService.java:47`, `backend/src/main/java/vn/signa/sign/internal/review/ReviewQueryService.java:69`.
- **Lỗi** Các truy vấn lấy dòng biển, trọng số, đề xuất và tổng số mục chạy riêng qua nhiều snapshot. Worker có thể commit giữa các truy vấn.
- **Kịch bản** Trang `PENDING` đọc được mục duy nhất. Một kiểm duyệt viên duyệt mục đó trước khi API đếm, nên cùng phản hồi có một mục và `total: 0`. Tương tự khi mở chi tiết, API có thể trả `version` và mã trước lúc worker cập nhật nhưng kèm trọng số và đề xuất của phiên bản mới. Khoá lạc quan bảo vệ thao tác ghi, còn người duyệt vẫn nhìn thấy dữ liệu mâu thuẫn và có thể gặp `409` dù vừa mở.
- **Cách sửa** Đọc phần dữ liệu MySQL của một phản hồi trong giao dịch chỉ đọc `REPEATABLE_READ` hoặc truy vấn dùng chung một snapshot. Gắn URL ký sau khi lấy xong snapshot. Thêm ca điều phối commit giữa hai lần đọc.
- **Độ chắc chắn** chắc chắn.

## S3 · MySQL rollback nhưng một phần phiên đã bị xoá khỏi Redis

- **Lát** 8.
- **Vị trí** `backend/src/main/java/vn/signa/identity/internal/UserAdminService.java:86`, `backend/src/main/java/vn/signa/identity/internal/UserAdminService.java:109`, `backend/src/main/java/vn/signa/identity/internal/RedisSessionStore.java:103`.
- **Lỗi** Thu hồi Redis trong giao dịch MySQL thực hiện nhiều lệnh xoá rời. Khi Redis lỗi giữa chừng, vai trò, trạng thái và audit rollback nhưng các lệnh Redis đã chạy vẫn có hiệu lực.
- **Kịch bản** Một tài khoản có hai phiên. Quản trị viên yêu cầu đổi vai trò. Redis xoá phiên thứ nhất rồi lỗi khi xử lý phiên thứ hai. PATCH thất bại, vai trò trong MySQL giữ nguyên và lịch sử không có quyết định, nhưng phiên thứ nhất bị đăng xuất.
- **Cách sửa** Dùng phiên bản xác thực trong MySQL để vô hiệu hoá phiên theo kết quả commit như ở phát hiện S1. Đưa xoá khoá Redis ra sau commit với cơ chế thử lại. Kiểm rollback giữ phiên hợp lệ trước khi thay đổi thực sự commit.
- **Độ chắc chắn** chắc chắn.

## Ghi chú liên quan đến lần chạy CI đầu tiên

Hai vấn đề dưới đây có từ nền trước lát 5 và nằm ngoài tổng số trên.

- `backend/src/test/java/vn/signa/db/SchemaIT.java:36` vẫn buộc phiên bản Flyway hiện tại là `3`. Từ nhánh lát 4 đã có V20. Nhánh review thêm V50, V51 và V70, nên assertion vẫn sẽ fail nếu tất cả migration chạy thành công. Kiểm rằng các migration dự kiến đã áp dụng theo phiên bản hoặc tên, cùng với kiểm tra danh sách pending rỗng.
- `backend/src/test/java/vn/signa/identity/BootstrapAdminIT.java:28` dùng chung MySQL với các IT khác. Nếu `CatalogAdminIT` hoặc `AdminUsersIT` đã tạo một `ADMIN`, `BootstrapAdmin.java:70` bỏ qua việc tạo tài khoản bootstrap và bài test không tìm được email mong đợi. Tách schema hoặc container cho ca cần DB chưa có `ADMIN`.
