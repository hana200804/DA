# 02 · Đặc tả yêu cầu

Tài liệu định nghĩa toàn bộ yêu cầu của hệ thống. Các tài liệu khác chỉ tham chiếu mã yêu cầu (`FR-xx`, `NFR-xx`, `AI-xx`), không định nghĩa lại.

## Mục lục

1. [Phát biểu vấn đề](#1-phát-biểu-vấn-đề)
2. [Mục tiêu](#2-mục-tiêu)
3. [Không thuộc mục tiêu](#3-không-thuộc-mục-tiêu)
4. [Tác nhân và phân quyền](#4-tác-nhân-và-phân-quyền)
5. [User story](#5-user-story)
6. [Yêu cầu chức năng](#6-yêu-cầu-chức-năng)
7. [Yêu cầu về mô hình nhận diện](#7-yêu-cầu-về-mô-hình-nhận-diện)
8. [Tiêu chí chấp nhận](#8-tiêu-chí-chấp-nhận)
9. [Yêu cầu phi chức năng](#9-yêu-cầu-phi-chức-năng)
10. [Chỉ số thành công](#10-chỉ-số-thành-công)
11. [Vấn đề cần làm rõ](#11-vấn-đề-cần-làm-rõ)
12. [Thời gian và phân kỳ](#12-thời-gian-và-phân-kỳ)

## 1. Phát biểu vấn đề

Người tham gia giao thông thường không hiểu hết ý nghĩa biển báo theo QCVN 41 và không biết trước đoạn đường phía trước có biển gì, vì bản đồ số phổ biến không có lớp dữ liệu biển báo đầy đủ và cập nhật. Thu thập dữ liệu biển thủ công thì tốn công và nhanh lỗi thời.

Hệ thống cần **nhận diện biển báo từ hình ảnh** bằng học sâu, cho phép người dùng **đóng góp** kết quả nhận diện thành dữ liệu biển có vị trí, để **kiểm duyệt viên phê duyệt** trước khi **hiển thị trên bản đồ số** và phục vụ **tra cứu**. Đề tài yêu cầu dùng và đánh giá ba mô hình YOLOv5, YOLOv8 và DETR.

## 2. Mục tiêu

| # | Mục tiêu | Đo bằng | Ngưỡng đạt |
| --- | --- | --- | --- |
| G1 | Chọn được mô hình nhận diện tốt nhất cho biển báo Việt Nam bằng số liệu | Báo cáo so sánh YOLOv5, YOLOv8, DETR theo [AI-03](#7-yêu-cầu-về-mô-hình-nhận-diện) | Đủ cả 3 mô hình, cùng dữ liệu, cùng giao thức đánh giá |
| G2 | Nhận diện chính xác | mAP@0.5 của mô hình tốt nhất trên tập test | ≥ 0,85 |
| G3 | Nhận diện thời gian thực trên điện thoại, không cần mạng | FPS trên thiết bị tham chiếu | ≥ 15 FPS |
| G4 | Bản đồ biển chính xác mà kiểm duyệt viên tốn ít công | Precision của biển đã phê duyệt; số quyết định của kiểm duyệt viên so với phê duyệt từng đề xuất | Precision ≥ 0,95; số quyết định giảm ≥ 60% |
| G5 | Người dùng tra được biển nhanh | Số thao tác từ màn hình chính tới ý nghĩa một biển | ≤ 3 thao tác |

## 3. Không thuộc mục tiêu

| Không làm | Lý do |
| --- | --- |
| Ứng dụng iOS | Tập trung một nền tảng để làm đủ sâu phần nhận diện trên máy |
| Dẫn đường, tìm tuyến | Không thuộc đề tài; chỉ là tích hợp dịch vụ bên ngoài |
| Đọc chữ trên biển phụ (khung giờ, loại xe) | Bài toán OCR riêng; biển phụ chỉ được phát hiện vùng |
| Huấn luyện lại tự động, cập nhật mô hình qua mạng | Cần vận hành lâu dài mới có giá trị; mô hình đóng gói cùng ứng dụng |
| Vạch kẻ đường, đèn tín hiệu, bảng chỉ dẫn lớn | Bài toán khác (phân đoạn, trạng thái đèn, đọc chữ) |
| Cảnh báo biển cấm, biển nguy hiểm bằng giọng nói | Ngoài đề tài; chỉ cảnh báo giới hạn tốc độ được làm như phần mở rộng (FR-16) |

## 4. Tác nhân và phân quyền

| Vai trò | Mã | Kênh | Quyền |
| --- | --- | --- | --- |
| Khách | — | Ứng dụng di động | Nhận diện, tra cứu, xem bản đồ |
| Người dùng | `USER` | Ứng dụng di động | Quyền của khách; gửi đề xuất, đánh giá, báo cáo; xem đề xuất của mình |
| Kiểm duyệt viên | `MODERATOR` | Web quản trị | Phê duyệt đề xuất, xử lý báo cáo, thêm và sửa biển trên bản đồ, xem lịch sử |
| Quản trị viên | `ADMIN` | Web quản trị | Quyền của kiểm duyệt viên; quản lý danh mục biển, người dùng, vai trò |

Mỗi tài khoản có đúng một vai trò. Phân quyền kiểm tra ở backend cho mọi API, không chỉ ẩn nút trên giao diện.

**Thuật ngữ dùng trong tài liệu:**

| Thuật ngữ | Nghĩa |
| --- | --- |
| Đề xuất | Một lần người dùng gửi "có biển mã X tại vị trí Y", kèm ảnh cắt và độ tin cậy của mô hình |
| Biển | Một biển báo thật trên đường, gom từ nhiều đề xuất; có trạng thái `PENDING`, `APPROVED`, `UNDER_REVIEW`, `REJECTED`, `REMOVED` |
| Điểm tin cậy | Mức tin rằng biển có thật và đúng mã, tính từ đề xuất và đánh giá — [Thiết kế AI §6](04-thiet-ke-ai.md) |
| Đánh giá | Người dùng xác nhận một biển trên bản đồ là "còn đúng" hoặc "không đúng" |
| Báo cáo | Người dùng báo một biển sai, kèm lý do, chờ kiểm duyệt viên xử lý |
| Lịch sử xử lý | Bản ghi mọi thay đổi của biển: ai, lúc nào, làm gì, trước và sau, lý do |

## 5. User story

### Khách và người dùng

| ID | User story | Ưu tiên |
| --- | --- | --- |
| US-01 | Là người đi đường, tôi muốn chụp một biển lạ và biết ngay đó là biển gì, nghĩa là gì, để không hiểu sai | P0 |
| US-02 | Là người lái xe, tôi muốn điện thoại nhận diện biển qua camera ngay cả khi mất sóng, để biết biển mình vừa đi qua | P0 |
| US-03 | Là người đi đường, tôi muốn xem các biển báo quanh tôi trên bản đồ, để biết trước đoạn đường phía trước | P0 |
| US-04 | Là người đi đường, tôi muốn tra một biển theo mã, tên hoặc nhóm, để học và kiểm tra ý nghĩa | P0 |
| US-05 | Là người dùng, tôi muốn gửi biển mình nhận diện được lên bản đồ, để người khác cũng biết | P0 |
| US-06 | Là người dùng, tôi muốn xác nhận một biển trên bản đồ còn đúng hay không, để bản đồ luôn cập nhật | P0 |
| US-07 | Là người dùng, tôi muốn báo một biển sai kèm lý do, để kiểm duyệt viên sửa | P0 |
| US-08 | Là người dùng, tôi muốn xem đề xuất của tôi đã được duyệt chưa, để biết đóng góp có ích | P1 |
| US-09 | Là người dùng, tôi muốn chỉ gửi dữ liệu qua Wi-Fi, để không tốn dung lượng di động | P1 |
| US-10 | Là người lái xe, tôi muốn được nhắc bằng giọng nói khi vượt giới hạn tốc độ vừa nhận diện, để không vi phạm | P2 |

### Kiểm duyệt viên và quản trị viên

| ID | User story | Ưu tiên |
| --- | --- | --- |
| US-20 | Là kiểm duyệt viên, tôi muốn các đề xuất trùng nhau được gom thành một biển, để chỉ quyết định một lần cho mỗi biển | P0 |
| US-21 | Là kiểm duyệt viên, tôi muốn thấy điểm tin cậy và gợi ý của hệ thống cho mỗi biển chờ duyệt, để duyệt nhanh và xem kỹ ca khó | P0 |
| US-22 | Là kiểm duyệt viên, tôi muốn sửa mã hoặc vị trí khi duyệt, để không phải từ chối đề xuất gần đúng | P0 |
| US-23 | Là kiểm duyệt viên, tôi muốn xử lý báo cáo biển sai, để gỡ hoặc sửa biển không đúng | P0 |
| US-24 | Là kiểm duyệt viên, tôi muốn xem lịch sử của một biển, để biết ai đã thay đổi gì và vì sao | P0 |
| US-25 | Là quản trị viên, tôi muốn quản lý danh mục loại biển, để ứng dụng hiển thị đúng ý nghĩa | P0 |
| US-26 | Là quản trị viên, tôi muốn quản lý người dùng và vai trò, để phân quyền kiểm duyệt | P0 |
| US-27 | Là quản trị viên, tôi muốn chạy một ảnh qua cả 3 mô hình và xem kết quả cạnh nhau, để minh hoạ và kiểm tra khác biệt | P1 |
| US-28 | Là quản trị viên, tôi muốn xem thống kê đề xuất, tỉ lệ duyệt và độ phủ bản đồ, để biết hệ thống hoạt động thế nào | P1 |

## 6. Yêu cầu chức năng

`P0` bắt buộc · `P1` nên có · `P2` mở rộng, chỉ làm khi xong P0 và P1.

### Ứng dụng di động

| ID | Yêu cầu | Ưu tiên | Story |
| --- | --- | --- | --- |
| FR-01 | Đăng ký, đăng nhập, đăng xuất, đổi mật khẩu | P0 | — |
| FR-02 | Nhận diện ảnh chụp hoặc ảnh chọn từ máy trên server, chọn được mô hình | P0 | US-01 |
| FR-03 | Nhận diện qua camera trên thiết bị, theo dõi biển qua nhiều khung hình | P0 | US-02 |
| FR-04 | Gửi đề xuất từ kết quả nhận diện: từ ảnh chụp (FR-02) hoặc tự động từ camera (FR-03) | P0 | US-05 |
| FR-05 | Bản đồ số hiển thị biển `APPROVED` và `UNDER_REVIEW` theo vùng đang xem | P0 | US-03 |
| FR-06 | Lọc biển trên bản đồ theo nhóm biển | P0 | US-03 |
| FR-07 | Chi tiết biển: ảnh, mã, tên, ý nghĩa, điểm tin cậy, số đánh giá | P0 | US-03 |
| FR-08 | Tra cứu danh mục biển theo mã, tên, nhóm | P0 | US-04 |
| FR-09 | Đánh giá biển: "còn đúng" hoặc "không đúng" | P0 | US-06 |
| FR-10 | Báo cáo biển sai kèm lý do | P0 | US-07 |
| FR-11 | Xem danh sách đề xuất của tôi và trạng thái | P1 | US-08 |
| FR-12 | Hàng đợi gửi khi mất mạng; tuỳ chọn chỉ gửi qua Wi-Fi | P1 | US-09 |
| FR-13 | Quên mật khẩu bằng mã OTP qua email | P1 | — |
| FR-14 | Tìm địa điểm để di chuyển bản đồ | P1 | US-03 |
| FR-15 | Tải trước biển của một vùng để xem khi mất mạng | P2 | US-03 |
| FR-16 | Cảnh báo giọng nói khi vượt giới hạn tốc độ vừa nhận diện | P2 | US-10 |

### Backend

| ID | Yêu cầu | Ưu tiên | Story |
| --- | --- | --- | --- |
| FR-20 | Gom đề xuất thành biển theo nhóm, khoảng cách và hướng nếu có | P0 | US-20 |
| FR-21 | Tính điểm tin cậy và gợi ý quyết định cho biển `PENDING` | P0 | US-21 |
| FR-22 | Cập nhật điểm tin cậy theo đánh giá; biển `APPROVED` bị nghi ngờ chuyển `UNDER_REVIEW` | P0 | US-06 |
| FR-23 | Ghi lịch sử mọi thay đổi trạng thái, mã, vị trí của biển | P0 | US-24 |
| FR-24 | Dịch vụ nhận diện phục vụ YOLOv5, YOLOv8, DETR | P0 | US-01, US-27 |
| FR-25 | Tự duyệt biển có điểm tin cậy rất cao, từ đủ nhiều người khác nhau | P1 | US-21 |

### Web quản trị

| ID | Yêu cầu | Ưu tiên | Story |
| --- | --- | --- | --- |
| FR-30 | Đăng nhập cho `MODERATOR`, `ADMIN` | P0 | — |
| FR-31 | Hàng đợi phê duyệt: duyệt, sửa rồi duyệt, từ chối | P0 | US-20, US-21, US-22 |
| FR-32 | Xử lý báo cáo biển sai | P0 | US-23 |
| FR-33 | Thêm, sửa, gỡ biển trực tiếp trên bản đồ | P0 | US-22 |
| FR-34 | Xem lịch sử xử lý theo biển và theo người thực hiện | P0 | US-24 |
| FR-35 | Quản lý danh mục nhóm biển và loại biển | P0 | US-25 |
| FR-36 | Quản lý người dùng: đổi vai trò, khoá, mở khoá | P0 | US-26 |
| FR-37 | So sánh 3 mô hình trên cùng một ảnh | P1 | US-27 |
| FR-38 | Thống kê: số đề xuất, tỉ lệ duyệt, thời gian xử lý, độ phủ | P1 | US-28 |

## 7. Yêu cầu về mô hình nhận diện

Phần nghiên cứu của đề tài. Thiết kế chi tiết: [Thiết kế AI](04-thiet-ke-ai.md); dữ liệu: [Dữ liệu và gán nhãn](05-du-lieu.md).

| ID | Yêu cầu | Ưu tiên |
| --- | --- | --- |
| AI-01 | Một danh mục nhãn dùng chung cho dữ liệu, mô hình, CSDL và ứng dụng, có số phiên bản | P0 |
| AI-02 | Huấn luyện YOLOv5, YOLOv8, DETR trên **cùng** tập train/val, cùng kích thước ảnh đầu vào | P0 |
| AI-03 | Đánh giá cả 3 mô hình trên **cùng** tập test và tập thực địa, theo cùng giao thức | P0 |
| AI-04 | Chọn mô hình triển khai cho server và cho điện thoại, ghi lý do bằng số liệu | P0 |
| AI-05 | Xuất mô hình điện thoại sang TFLite và đo trên thiết bị tham chiếu | P0 |
| AI-06 | Mỗi mô hình phát hành có thẻ mô hình: dữ liệu, cấu hình, chỉ số, hạn chế | P0 |
| AI-07 | Thí nghiệm bổ sung: biển nhỏ (ảnh đầu vào lớn hơn), lượng tử hoá INT8 và FP16 | P1 |

## 8. Tiêu chí chấp nhận

### FR-01 · Tài khoản

- [ ] Đăng ký bằng email và mật khẩu ≥ 8 ký tự; email trùng báo lỗi rõ ràng
- [ ] Đăng nhập trả về phiên có thời hạn; đăng xuất thu hồi phiên
- [ ] Đổi mật khẩu yêu cầu mật khẩu cũ và thu hồi mọi phiên khác
- [ ] Tài khoản bị khoá không đăng nhập được

### FR-02 · Nhận diện ảnh trên server

- **Cho trước** người dùng đã chụp hoặc chọn một ảnh có biển thuộc danh mục,
  **khi** gửi ảnh để nhận diện,
  **thì** ứng dụng hiển thị khung từng biển, mã, tên, ý nghĩa và độ tin cậy, kèm tên mô hình và thời gian xử lý.
- [ ] Chọn được YOLOv5, YOLOv8, DETR; mặc định là mô hình được chọn ở AI-04
- [ ] Ảnh không có biển: thông báo "Không tìm thấy biển báo"
- [ ] Ảnh lớn hơn 10 MB hoặc không phải ảnh: báo lỗi trước khi gửi
- [ ] Khách dùng được, có giới hạn số lần mỗi phút
- [ ] Ảnh gốc không được lưu lại sau khi nhận diện (NFR-09)

### FR-03 · Nhận diện qua camera

- **Cho trước** ứng dụng ở chế độ camera và đã có quyền camera,
  **khi** một biển thuộc danh mục xuất hiện với chiều cao ≥ 24 px,
  **thì** biển được khoanh vùng và gán mã mà không cần mạng.
- [ ] Ở chế độ máy bay vẫn nhận diện được
- [ ] Một biển chỉ được hiển thị là "đã xác nhận" khi xuất hiện ở ít nhất 3 trong 5 khung liên tiếp
- [ ] Không có biển trong khung hình thì không hiển thị khung nào
- [ ] Màn hình không tự tắt khi đang ở chế độ camera

### FR-04 · Gửi đề xuất

- **Cho trước** người dùng đã đăng nhập và bật đóng góp dữ liệu,
  **khi** một biển được xác nhận ở chế độ camera và có vị trí GPS, hoặc người dùng bấm "Gửi lên bản đồ" trên kết quả nhận diện ảnh,
  **thì** một đề xuất gồm mã, độ tin cậy, vị trí, hướng, ảnh cắt vùng biển, phiên bản mô hình được gửi lên server.
- [ ] Ở chế độ camera, mỗi biển sinh đúng **một** đề xuất cho mỗi lần đi qua
- [ ] Với ảnh chụp, vị trí lấy từ GPS lúc chụp; không có GPS thì người dùng ghim vị trí trên bản đồ
- [ ] Người dùng sửa được mã trước khi gửi đề xuất từ ảnh chụp
- [ ] Gửi lại cùng một đề xuất không tạo bản ghi trùng
- [ ] Khách không gửi được đề xuất

### FR-05 · Bản đồ số

- **Cho trước** người dùng mở màn hình bản đồ,
  **khi** bản đồ hiển thị một vùng,
  **thì** các biển `APPROVED` và `UNDER_REVIEW` trong vùng được hiển thị bằng biểu tượng của mã biển.
- [ ] Kéo hoặc phóng bản đồ thì tải biển của vùng mới
- [ ] Khi vùng có hơn 500 biển sau khi lọc, các biển được gộp theo ô lưới phù hợp mức zoom, mỗi ô có số đếm
- [ ] Biển `UNDER_REVIEW` có dấu hiệu "đang rà soát"
- [ ] Biển `PENDING`, `REJECTED`, `REMOVED` không hiển thị
- [ ] Chạm vào biển thì mở chi tiết (FR-07)

### FR-06 · Lọc theo nhóm

- [ ] Lọc theo một hoặc nhiều nhóm biển
- [ ] Bộ lọc được giữ lại khi mở lại ứng dụng

### FR-07 · Chi tiết biển

- [ ] Gồm ảnh cắt đại diện, ảnh minh hoạ chuẩn, mã, tên, nhóm, ý nghĩa, điểm tin cậy, số đánh giá đúng/sai, ngày cập nhật
- [ ] Có nút đánh giá (FR-09) và báo cáo (FR-10) khi đã đăng nhập

### FR-08 · Tra cứu danh mục

- [ ] Tìm theo mã (`P.123a`) hoặc một phần tên, không phân biệt hoa thường và dấu
- [ ] Duyệt theo nhóm biển
- [ ] Chi tiết loại biển: ảnh minh hoạ, mã, tên, ý nghĩa theo QCVN 41
- [ ] Từ chi tiết loại biển, mở bản đồ lọc theo loại biển đó
- [ ] Dùng được khi mất mạng (danh mục đóng gói cùng ứng dụng, cập nhật khi có mạng)

### FR-09 · Đánh giá biển

- [ ] Mỗi người dùng có một đánh giá cho mỗi biển; đánh giá lại thì ghi đè
- [ ] Đánh giá cập nhật điểm tin cậy (FR-22)
- [ ] Người đã gửi đề xuất cho biển đó không đánh giá được biển đó

### FR-10 · Báo cáo biển sai

- [ ] Bắt buộc chọn lý do: không tồn tại, sai loại biển, sai vị trí, khác; "khác" bắt buộc nhập mô tả
- [ ] Tuỳ chọn đính kèm một ảnh
- [ ] Mỗi người chỉ có một báo cáo đang chờ xử lý cho mỗi biển
- [ ] Người báo cáo nhận được kết quả xử lý trong danh sách báo cáo của mình

### FR-11 · Đề xuất của tôi

- [ ] Danh sách đề xuất với trạng thái: đang chờ, đã được duyệt, bị từ chối kèm lý do

### FR-12 · Hàng đợi gửi

- **Cho trước** thiết bị không có mạng phù hợp với cài đặt,
  **khi** có đề xuất mới,
  **thì** đề xuất được lưu trên máy và tự gửi khi có mạng phù hợp.
- [ ] Tuỳ chọn "chỉ gửi qua Wi-Fi", mặc định bật
- [ ] Hàng đợi tối đa 500 đề xuất; khi đầy, bỏ đề xuất cũ nhất

### FR-13 · Quên mật khẩu

- [ ] Gửi mã OTP 6 số qua email, hiệu lực 10 phút, tối đa 5 lần nhập sai
- [ ] Không tiết lộ email có tồn tại trong hệ thống hay không

### FR-14 · Tìm địa điểm

- [ ] Nhập từ khoá, chọn kết quả thì bản đồ di chuyển tới đó

### FR-15 · Tải trước vùng

- [ ] Chọn vùng trên bản đồ, tải biển của vùng đó; xem được khi mất mạng

### FR-16 · Cảnh báo tốc độ

- **Cho trước** camera vừa xác nhận biển tốc độ tối đa 50 km/h,
  **khi** tốc độ GPS vượt 53 km/h liên tục ít nhất 3 giây,
  **thì** ứng dụng đọc "Vượt tốc độ, giới hạn 50".
- [ ] Gặp biển hết hạn chế tốc độ thì trở về "chưa rõ giới hạn" và không cảnh báo
- [ ] Nhắc lại sau 30 giây nếu vẫn vượt

### FR-20 · Gom đề xuất thành biển

- **Cho trước** hai người dùng khác nhau gửi đề xuất cùng nhóm biển, cùng hướng, cách nhau trong bán kính gom,
  **khi** backend xử lý đề xuất,
  **thì** hai đề xuất thuộc cùng một biển `PENDING`.
- [ ] Không có biển phù hợp thì tạo biển `PENDING` mới
- [ ] Đề xuất gần một biển `APPROVED` cùng mã được tính như một xác nhận cho biển đó, không tạo biển mới
- [ ] Nếu chỉ khớp biển `APPROVED` hoặc `UNDER_REVIEW` khác mã, tạo biển `PENDING` mới và gắn cờ "khác với biển đã duyệt"
- [ ] Nếu khớp biển bị từ chối cùng mã trong 30 ngày và không có biển đang hoạt động phù hợp, từ chối đề xuất theo quy tắc tại [Thiết kế AI §6](04-thiet-ke-ai.md#6-gom-cụm-và-điểm-tin-cậy)

### FR-21 · Điểm tin cậy và gợi ý

- [ ] Mỗi biển `PENDING` có điểm tin cậy trong [0; 1], mã dẫn đầu, số người đề xuất khác nhau
- [ ] Gợi ý một trong: **Nên duyệt**, **Cần xem kỹ** (có mã cạnh tranh hoặc khác biển đã duyệt), **Chờ thêm** (chưa đủ bằng chứng)
- [ ] Hàng đợi sắp xếp mặc định: "Nên duyệt" trước, rồi theo điểm giảm dần
- [ ] Nhiều đề xuất từ một người cho cùng một biển chỉ được tính trọng số giới hạn

### FR-22 · Đánh giá cập nhật điểm

- [ ] Biển `APPROVED` có điểm tin cậy giảm dưới ngưỡng rà soát, hoặc có ≥ 3 đánh giá "không đúng" từ người khác nhau, chuyển `UNDER_REVIEW` và vào hàng đợi phê duyệt

### FR-23 · Lịch sử xử lý

- [ ] Mọi thay đổi trạng thái, mã, vị trí của biển ghi một dòng: thời điểm, người hoặc hệ thống thực hiện, hành động, giá trị trước, giá trị sau, lý do
- [ ] Lịch sử không sửa, không xoá được qua API

### FR-24 · Dịch vụ nhận diện

- [ ] Một API nhận ảnh và tên mô hình, trả danh sách box, mã, độ tin cậy, thời gian suy luận
- [ ] Nạp sẵn cả 3 mô hình khi khởi động
- [ ] Trả phiên bản mô hình và phiên bản danh mục nhãn trong mọi kết quả

### FR-25 · Tự duyệt

- [ ] Biển `PENDING` tự chuyển `APPROVED` khi đạt các ngưỡng điểm, số người gửi và tỉ lệ mã cạnh tranh tại [Thiết kế AI §6](04-thiet-ke-ai.md#6-gom-cụm-và-điểm-tin-cậy)
- [ ] Lịch sử ghi người thực hiện là "hệ thống"
- [ ] Tự duyệt mặc định tắt và có thể bật bằng cấu hình

### FR-30 · Đăng nhập quản trị

- [ ] Chỉ `MODERATOR`, `ADMIN` đăng nhập được web quản trị
- [ ] Mọi API quản trị trả `403` với vai trò khác; API quản lý danh mục và người dùng trả `403` với `MODERATOR`

### FR-31 · Phê duyệt

- **Cho trước** một biển `PENDING`,
  **khi** kiểm duyệt viên mở biển,
  **thì** thấy vị trí trên bản đồ, các ảnh cắt của đề xuất, phân bố mã, điểm tin cậy, gợi ý, và biển đã duyệt gần đó.
- [ ] **Duyệt**: biển chuyển `APPROVED` với mã dẫn đầu
- [ ] **Sửa rồi duyệt**: đổi mã hoặc kéo vị trí trước khi duyệt
- [ ] **Từ chối**: bắt buộc nhập lý do; biển chuyển `REJECTED`
- [ ] Với biển `UNDER_REVIEW`: giữ (`APPROVED`), sửa, hoặc gỡ (`REMOVED`)
- [ ] Hai kiểm duyệt viên không xử lý chồng lên nhau: người thứ hai nhận lỗi "biển đã được xử lý"
- [ ] Mọi quyết định ghi lịch sử (FR-23); đề xuất thành viên cập nhật trạng thái theo biển

### FR-32 · Xử lý báo cáo

- [ ] Chấp nhận báo cáo: sửa biển hoặc gỡ biển trong cùng thao tác
- [ ] Từ chối báo cáo: bắt buộc nhập lý do
- [ ] Báo cáo cùng biển được hiển thị cùng nhau

### FR-33 · Sửa biển trực tiếp

- [ ] Thêm biển mới tại vị trí chọn trên bản đồ, trạng thái `APPROVED`
- [ ] Sửa mã, vị trí, hướng; gỡ biển (`REMOVED`) kèm lý do
- [ ] Mọi thao tác ghi lịch sử

### FR-34 · Xem lịch sử

- [ ] Lọc theo biển, người thực hiện, hành động, khoảng thời gian

### FR-35 · Danh mục biển

- [ ] Thêm, sửa nhóm biển và loại biển: mã, nhóm, tên, ý nghĩa, ảnh minh hoạ, giá trị tốc độ (biển tốc độ)
- [ ] Không xoá loại biển đang được dùng; chỉ ngừng sử dụng
- [ ] Mã loại biển phải có trong danh mục nhãn hiện hành, hoặc được đánh dấu "chỉ nhập tay" (mô hình không nhận diện)

### FR-36 · Người dùng

- [ ] Tìm theo email; đổi vai trò; khoá, mở khoá
- [ ] Khoá tài khoản thu hồi mọi phiên của tài khoản đó
- [ ] Quản trị viên không tự hạ vai trò hay tự khoá chính mình

### FR-37 · So sánh mô hình

- [ ] Tải một ảnh, hiển thị kết quả 3 mô hình cạnh nhau: box, mã, độ tin cậy, thời gian suy luận
- [ ] Hiển thị bảng chỉ số đánh giá của từng mô hình (AI-03)

### FR-38 · Thống kê

- [ ] Số đề xuất theo ngày, tỉ lệ duyệt/từ chối, thời gian từ đề xuất tới quyết định, số biển theo nhóm, bản đồ mật độ biển

## 9. Yêu cầu phi chức năng

| ID | Nhóm | Yêu cầu | Chỉ tiêu |
| --- | --- | --- | --- |
| NFR-01 | Hiệu năng | Tốc độ nhận diện qua camera trên thiết bị tham chiếu | ≥ 15 FPS |
| NFR-02 | Hiệu năng | Thời gian nhận diện ảnh trên server tham chiếu, đo từ khi nhận ảnh tới khi trả kết quả | YOLO: p95 ≤ 1 s; DETR: p95 ≤ 3 s |
| NFR-03 | Hiệu năng | Truy vấn biển theo vùng (≤ 500 biển) | p95 < 300 ms |
| NFR-04 | Hiệu năng | Tiếp nhận đề xuất | ≥ 50 đề xuất/giây |
| NFR-05 | Độ chính xác | mAP@0.5 trên tập test: mô hình server / mô hình điện thoại | ≥ 0,85 / ≥ 0,80 |
| NFR-06 | Độ chính xác | Recall nhóm biển cấm (gồm biển tốc độ) của mô hình điện thoại | ≥ 0,85 |
| NFR-07 | Tài nguyên | Kích thước mô hình đóng gói trong ứng dụng | < 15 MB |
| NFR-08 | Khả dụng | Nhận diện qua camera và tra cứu danh mục hoạt động không cần mạng | 100% FR-03, FR-08 |
| NFR-09 | Riêng tư | Lưu ảnh cắt của đề xuất và ảnh đính kèm báo cáo trong các bucket riêng tư; ảnh gốc gửi để nhận diện không được lưu; không lưu lộ trình di chuyển | Review mã và kiểm tra bucket |
| NFR-10 | Bảo mật | Mật khẩu băm BCrypt; phiên có thời hạn và thu hồi được; phân quyền theo vai trò ở mọi API; giới hạn tần suất đăng nhập và nhận diện; HTTPS ở môi trường demo | Đạt các ca kiểm thử bảo mật |
| NFR-11 | Truy vết | Tạo biển và thay đổi trạng thái, mã hoặc vị trí phải có lịch sử xử lý trong cùng giao dịch | 100%, kiểm tra bằng test tích hợp |
| NFR-12 | Tương thích | Android 8.0 (API 26) trở lên; GPU delegate, dự phòng CPU | Chạy trên ≥ 2 thiết bị khác phân khúc |
| NFR-13 | Bảo trì | Test tự động cho backend, dịch vụ nhận diện, logic trên ứng dụng; API mô tả bằng OpenAPI; cấu hình qua biến môi trường | CI xanh |

Thiết bị và server tham chiếu, cách đo từng chỉ tiêu: [08 · Kiểm thử](08-kiem-thu.md).

## 10. Chỉ số thành công

Đồ án không có người dùng thật, nên chỉ số được đo trong quá trình phát triển và trong thử nghiệm thực địa của nhóm.

### Chỉ số sớm — theo dõi hằng tuần

| Chỉ số | Mốc |
| --- | --- |
| Số ảnh và số box đã gán nhãn, theo lớp | Đủ danh mục v1 ở tuần 5 |
| mAP@0.5 baseline của từng mô hình trên tập val | Có cả 3 ở tuần 7 |
| FPS mô hình điện thoại trên thiết bị tham chiếu | Đo lần đầu ở tuần 8 |
| Luồng đầu–cuối ảnh → đề xuất → duyệt → bản đồ | Chạy được ở tuần 11 |

### Chỉ số cuối — đo trong tuần 13–14

| Chỉ số | Đạt | Mục tiêu cao | Cách đo |
| --- | --- | --- | --- |
| mAP@0.5 mô hình tốt nhất, tập test | ≥ 0,85 | ≥ 0,90 | [Kiểm thử §3](08-kiem-thu.md) |
| mAP@0.5 mô hình điện thoại, tập thực địa | ≥ 0,75 | ≥ 0,85 | Khung trích từ video thực địa |
| FPS trên thiết bị tham chiếu | ≥ 15 | ≥ 25 | Phát lại video thực địa |
| Precision biển `APPROVED` so với danh sách biển thật trên tuyến thử | ≥ 0,95 | ≥ 0,98 | [Kiểm thử §6](08-kiem-thu.md) |
| Recall biển thật trên tuyến thử sau 3 lượt đi | ≥ 0,70 | ≥ 0,85 | Như trên |
| Số quyết định kiểm duyệt so với duyệt từng đề xuất | Giảm ≥ 60% | Giảm ≥ 80% | Như trên |
| Tỉ lệ gợi ý "Nên duyệt" trùng quyết định của kiểm duyệt viên | ≥ 90% | ≥ 95% | Như trên |

## 11. Vấn đề cần làm rõ

| # | Câu hỏi | Ảnh hưởng | Người trả lời | Chặn công việc |
| --- | --- | --- | --- | --- |
| Q1 | Danh mục v1 gồm những mã nào — đề xuất 40–60 mã phổ biến nhất có đủ dữ liệu. **Đã chốt:** 42 mã QCVN đủ 100 box train trong VN82 và 4 lớp `other_<nhóm>` ([labels.yaml](../ai/data/labels.yaml)) | AI-01, gán nhãn | Thành viên 1, duyệt với GVHD | Có — tuần 2 |
| Q2 | "DETR" trong đề tài có chấp nhận RT-DETR (họ DETR, thời gian thực) hay bắt buộc DETR gốc. **Đã chốt:** dùng DETR-R50 gốc làm mô hình bắt buộc để khớp đề tài; RT-DETR-L là thử nghiệm bổ sung | AI-02 | Thành viên 1 | Không còn chặn |
| Q3 | Bộ dữ liệu công khai nào được phép dùng theo giấy phép | Nguồn dữ liệu | Thành viên 1 | Có — tuần 2 |
| Q4 | GPU để huấn luyện (máy phòng lab, Kaggle, Colab) và giới hạn thời gian | Lịch huấn luyện, nhất là DETR | Cả nhóm | Có — tuần 3 |
| Q5 | Nhóm dữ liệu "đánh giá" trong đề cương có đúng nghĩa là người dùng xác nhận đúng/sai biển | FR-09, CSDL | GVHD | Không |
| Q6 | Bản đồ nền: MapLibre với dữ liệu OpenStreetMap hay VietMap SDK | FR-05, FR-14 | Thành viên 3 | Không |
| Q7 | Thiết bị Android tham chiếu và cấu hình server tham chiếu | NFR-01, NFR-02 | Thành viên 2, 3 | Không |

## 12. Thời gian và phân kỳ

Tổng 16 tuần. Hạn cứng: nộp báo cáo và bảo vệ cuối tuần 16. Đóng băng tính năng cuối tuần 12; tuần 13–16 chỉ sửa lỗi, đánh giá và viết báo cáo.

| Giai đoạn | Tuần | Đầu ra |
| --- | --- | --- |
| 1 · Chốt thiết kế | 1–2 | Bộ tài liệu được duyệt, danh mục nhãn v1, hợp đồng API và câu trả lời Q1–Q3 |
| 2 · Nền tảng | 3–6 | Chốt GPU (Q4) trong tuần 3. Hoàn thành dữ liệu v1, backend tài khoản, danh mục, biển và bản đồ, cùng ứng dụng tra cứu và nhận diện ảnh qua server với mô hình tạm |
| 3 · Lõi | 7–10 | Cả 3 mô hình huấn luyện xong; nhận diện trên điện thoại; đề xuất, gom cụm, điểm tin cậy, phê duyệt, đánh giá, báo cáo, lịch sử |
| 4 · Tích hợp | 11–12 | Luồng đầu–cuối; mô hình triển khai cuối; P1 nếu kịp |
| 5 · Đánh giá | 13–14 | Thử nghiệm thực địa, đo mọi chỉ số ở §10, sửa lỗi |
| 6 · Hoàn thiện | 15–16 | Báo cáo đồ án, slide, bản demo |

**Thứ tự cắt khi chậm tiến độ:** FR-16 → FR-15 → FR-25 → FR-14 → FR-12 → FR-37, FR-38. Không cắt AI-02, AI-03 và FR-31 vì đây là nội dung bắt buộc của đề tài.

Kế hoạch chi tiết theo thành viên: [09 · Hạng mục công việc](09-hang-muc-cong-viec.md).
