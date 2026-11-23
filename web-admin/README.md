# VisionMap — Web quản trị biển báo

Web được viết mới bằng **React + TypeScript + Vite**, dựa trên phạm vi website quản trị trong tài liệu đồ án và hợp đồng API của [walacker/traffic-sign](https://github.com/walacker/traffic-sign). Không dùng mã giao diện cũ.

## Chạy nhanh bằng dữ liệu mẫu

Nếu dùng bản ZIP đã có `dist`, chạy lệnh dưới đây để xem demo mà không cần cài thư viện. Khi clone repo, dùng `npm ci` và `npm run dev` theo hướng dẫn bên dưới.

```powershell
node scripts/preview-demo.mjs
```

Mở <http://127.0.0.1:5173>. Nhấn Ctrl+C trong terminal để dừng. Script này chỉ phục vụ bản demo đã build; để sửa mã hoặc kết nối backend, dùng quy trình bên dưới.

Cần Node.js **22.18 trở lên** và npm. Mở terminal tại thư mục này:

```powershell
npm ci
npm run dev
```

Mở <http://127.0.0.1:5173>, chọn **Vào không gian trải nghiệm**. Không cần tài khoản hoặc backend. Mặc định là chế độ `demo`.

- Có 16 biển báo mẫu, 5 nhóm biển, 38 bản ghi đăng tải và 6 người đóng góp.
- Có thể sửa tên, duyệt, từ chối, xử lý báo cáo và xóa bản ghi từ lịch sử người dùng.
- Dữ liệu mẫu được lưu trong `localStorage`; nút **Đặt lại dữ liệu** khôi phục bộ dữ liệu ban đầu.
- Ảnh minh họa là SVG phục vụ trình bày giao diện; không phải ảnh chụp thực tế hoặc bộ ảnh biển báo chuẩn.
- Biểu đồ tuần được tính từ ngày đăng tải của bộ dữ liệu mẫu, ghi rõ thời gian mẫu.
- Phiên đăng nhập lưu trong `sessionStorage`, tách biệt giữa demo và API.

## Kiểm duyệt biển theo đặc tả Signa (FR-31)

Bản demo hiển thị hàng đợi theo hồ sơ biển, gồm hồ sơ có hai mã cạnh tranh và biển đang rà soát. Mở **Biển báo chờ duyệt**, rồi chọn một hồ sơ để duyệt, sửa rồi duyệt hoặc từ chối. Khi sửa vị trí, kéo ghim trên bản đồ hoặc nhập tọa độ. Có thể đặt lại dữ liệu mẫu từ banner.

Để thử với backend Signa, sao chép `.env.example` thành `.env`, đặt `VITE_DATA_MODE=api`, `VITE_API_CONTRACT=signa` và `SIGNA_TARGET` theo địa chỉ gateway. Chạy `npm run dev`, đăng nhập bằng email có quyền `review:decide`. Dockerfile đi kèm phục vụ `/api/v1` và `/icons/` qua cùng origin với web. Có thể cấu hình nguồn bản đồ bằng `VITE_REVIEW_TILE_URL`.

Đợt tích hợp Signa gồm đăng nhập, hàng đợi duyệt, xử lý báo cáo, quản lý biển trực tiếp trên bản đồ, tra cứu lịch sử, quản lý danh mục, quản lý người dùng, so sánh mô hình, thống kê và số liệu hai hàng đợi trên trang Tổng quan. Các trang nghiệp vụ khác vẫn có trong chế độ demo và chế độ API `legacy`. Dạng phản hồi hàng đợi và chi tiết trong `src/reviewTypes.ts` cần đối chiếu với OpenAPI của backend khi tích hợp. Với biển đang rà soát, thao tác sửa tải lại hồ sơ sau `PATCH`; nếu vẫn đang rà soát thì gửi `keep` với phiên bản mới.

## Xử lý báo cáo biển sai theo đặc tả Signa (FR-32)

Mở **Báo cáo cộng đồng** để xem các báo cáo theo từng biển. Trong hộp thoại, chọn một báo cáo rồi chọn **Chấp nhận và sửa biển**, **Chấp nhận và gỡ biển** hoặc **Từ chối**. Từ chối yêu cầu nhập lý do. Bộ lọc trạng thái cho phép xem lại kết quả. Nếu đã dùng bản demo trước, chọn **Đặt lại dữ liệu** để thử nhóm mẫu có hai báo cáo.

Adapter gọi `GET /api/v1/admin/reports`, `POST /api/v1/admin/reports/{id}/accept` hoặc `POST /api/v1/admin/reports/{id}/dismiss` bằng Bearer token. Do đặc tả chưa có ví dụ JSON cho FR-32, adapter dùng trang `items` gồm các nhóm `{ sign, reports }` và gửi `{ action, version, changes }` khi sửa. Cần đối chiếu hai cấu trúc này với OpenAPI backend khi tích hợp. Việc chấp nhận và thay đổi biển diễn ra bằng một yêu cầu để backend xử lý trong cùng giao dịch.

## Quản lý biển trực tiếp trên bản đồ (FR-33)

Mở **Bản đồ biển báo**. Chọn **Thêm biển**, đặt ghim trên bản đồ và chọn mã trong danh mục. Khi chọn một biển đã hiển thị, có thể sửa mã, kéo ghim, đổi hướng từ 0 đến 359 độ hoặc để trống nếu chưa biết. **Gỡ biển** yêu cầu lý do. Có thể kéo bản đồ, dùng nút điều hướng hoặc tới một tọa độ cụ thể.

Ở chế độ Signa, VisionMap tải biển theo khung nhìn từ `/api/v1/signs` và lấy `version` từ chi tiết quản trị. Các thao tác tạo, sửa và gỡ dùng `/api/v1/admin/signs`. Ứng dụng gửi mã, vị trí và `headingDeg` (hoặc `null`), kèm `version` khi sửa hoặc gỡ. Sau lỗi xung đột, bản đồ và chi tiết được tải lại. Đối chiếu payload với OpenAPI khi tích hợp.

Bản demo lưu các thao tác tạo, sửa và gỡ cùng lịch sử mẫu trong bộ nhớ trình duyệt. Backend Signa chịu trách nhiệm ghi lịch sử thật trong cùng giao dịch. Các bản ghi được hiển thị ở mục **Lịch sử xử lý**.

## Tra cứu lịch sử xử lý (FR-34)

Mở **Lịch sử xử lý** hoặc chọn **Xem lịch sử biển** từ chi tiết trên bản đồ. Lọc theo ID biển, ID người thực hiện, hành động và khoảng ngày. Bấm vào ID biển hoặc tên người thực hiện trên bảng để lọc nhanh. Chi tiết hiển thị dữ liệu trước và sau, lý do, thời điểm và mã truy vết nếu có. Bộ lọc ngày dùng múi giờ UTC+7 và gửi mốc UTC đến máy chủ.

Chế độ Signa gọi `GET /api/v1/admin/audit-logs` với quyền `audit:read`. Danh sách dùng định dạng phân trang chung và tên trường camelCase từ lược đồ audit. Cần đối chiếu DTO với OpenAPI của backend khi tích hợp. Demo ghi lịch sử cho các thao tác duyệt biển, xử lý báo cáo và thay đổi biển trực tiếp. Các bản ghi bản đồ từ bản demo FR-33 vẫn đọc được.

## Quản lý nhóm và loại biển (FR-35)

Mở **Nhóm biển báo** để thêm hoặc sửa tên, mô tả và thứ tự hiển thị. Trong **Danh mục biển báo**, quản trị viên có thể thêm loại **chỉ nhập tay**, sửa nhóm, tên, ý nghĩa, tốc độ, ảnh và trạng thái. Mã là khóa định danh của bản ghi. Để ngừng sử dụng loại biển, chọn trạng thái **Ngừng sử dụng**. Demo giữ loại biển trong lịch sử và loại nó khỏi danh sách chọn khi thêm biển trên bản đồ.

Chế độ Signa tải danh mục từ `/api/v1/sign-categories` và `/api/v1/sign-types`. API công khai theo đặc tả chỉ trả các loại đang dùng, nên bộ lọc loại ngừng sử dụng có dữ liệu trong demo hoặc khi nguồn đọc trả thêm các loại đó. Các thao tác ghi cần quyền `catalog:manage` và dùng các endpoint quản trị của FR-35.

Ảnh được gửi bằng multipart qua `PUT /api/v1/admin/sign-types/{code}/icon`. Adapter dùng trường `icon` và thuộc tính `recognizable: false` khi tạo thủ công. Cần xác nhận tên trường và payload với OpenAPI backend. Nếu thông tin đã lưu nhưng tải ảnh lỗi, hộp thoại cho thử tải ảnh lại. Gateway Signa phục vụ ảnh ở `/icons/`.

## Quản lý người dùng (FR-36)

Mở **Quản lý người dùng** để tìm theo email và lọc theo vai trò hoặc trạng thái. Mở chi tiết tài khoản rồi xác nhận đổi vai trò, khóa hoặc mở khóa. VisionMap dùng ID trả từ đăng nhập để nhận biết tài khoản của chính quản trị viên. Phiên cũ thiếu ID sẽ đối chiếu email. Tài khoản đang đăng nhập không có nút tự hạ vai trò hoặc tự khóa.

Chế độ Signa yêu cầu quyền `user:manage` và dùng `GET /api/v1/admin/users?q=&role=&status=` cùng `PATCH /api/v1/admin/users/{id}`. Mỗi thao tác chỉ gửi trường `role` hoặc `status` cần đổi. Backend chịu trách nhiệm kiểm tra quyền, ngăn tự khóa/tự hạ vai trò và thu hồi mọi phiên của tài khoản bị khóa. Demo mô phỏng trạng thái và ghi lịch sử xử lý, không có phiên thật của các tài khoản mẫu.

## So sánh mô hình (FR-37)

Mở **So sánh mô hình**, chọn một ảnh JPEG, PNG hoặc WebP tối đa 10 MB rồi bấm **So sánh 3 mô hình**. Ba kết quả dùng cùng ảnh, hiển thị khung, mã, độ tin cậy và thời gian suy luận. Trong demo, kết quả suy luận chỉ mang tính minh họa và ảnh ở trong trình duyệt.

Chế độ Signa yêu cầu `recognition:compare` và gửi một yêu cầu multipart có trường `image` đến `POST /api/v1/admin/recognition/compare`. Adapter giả định phản hồi có `results`, mỗi mục theo định dạng nhận diện ảnh của đặc tả. Bảng đánh giá đọc các mục `modelCard.val` và `modelCard.test` từ `GET /api/v1/recognition/models`, đồng thời hỗ trợ `modelCard.evaluations` của bản cũ. Cần đối chiếu hai cấu trúc phản hồi với OpenAPI khi tích hợp.

Khi thiếu kết quả đánh giá YOLOv8 từ máy chủ, bảng dùng số tham khảo từ `C:/DA/ai/experiments/yolov8n-640-v1.1-temp-s0/meta.json` và ghi rõ tập validation. Các mô hình thiếu kết quả được đánh dấu tương ứng.

## Thống kê (FR-38)

Mở **Thống kê** để xem số đề xuất từng ngày, tỉ lệ duyệt và từ chối, thời gian xử lý trung bình, số biển theo nhóm và mật độ trên bản đồ. Bộ lọc ngày tính theo UTC+7. Tỉ lệ dùng số đề xuất đã có quyết định làm mẫu số. Thời gian trung bình chỉ tính đề xuất có cả thời điểm gửi và thời điểm quyết định. Số biển và mật độ trong demo tính trên biển đang hiển thị được tạo trong khoảng đã chọn.

Chế độ Signa gọi `GET /api/v1/admin/stats?from=&to=` với quyền `stats:read`, gửi mốc UTC và nhận `submissionsByDay`, `decisions`, `processingTime`, `signsByCategory`, `densityCells`. Đặc tả chưa có mẫu phản hồi cho endpoint này. Cần đối chiếu các trường, đơn vị thời gian (ms) và cách tính với OpenAPI của backend khi tích hợp.

## Đăng nhập và phân quyền (FR-30)

Chế độ Signa đăng nhập bằng email với `client: WEB`. Chỉ `MODERATOR` và `ADMIN` có quyền `review:decide` mới vào được web. VisionMap lọc menu, kiểm tra đường dẫn trực tiếp theo quyền và chỉ cho `ADMIN` có quyền tương ứng ghi danh mục hoặc quản lý người dùng. Kiểm duyệt viên vẫn có thể tra cứu danh mục công khai.

VisionMap lưu token, email và hạn phiên trong `sessionStorage`. Khi tải lại, web lấy lại danh tính và quyền từ `GET /api/v1/me` trước khi mở màn hình. Lỗi `401` từ yêu cầu có phiên đưa người dùng về đăng nhập. Lỗi `403` tại chức năng được hiển thị mà phiên vẫn giữ. Backend Signa thực thi quyền ở từng API và xử lý thu hồi phiên qua `POST /api/v1/auth/logout`.

## Các màn hình

| Màn hình           | Chức năng                                                                                                   |
| ------------------ | ----------------------------------------------------------------------------------------------------------- |
| Tổng quan          | Tổng số biển trong danh mục, biển chờ duyệt, báo cáo, người đóng góp; các mục cần xem xét                   |
| Danh mục biển báo  | Tìm tên, lọc loại, phân trang, xem chi tiết, sửa tên, xuất CSV trang hiện tại                               |
| Loại biển báo      | Tìm loại và xem mô tả phân nhóm                                                                             |
| Biển báo chờ duyệt | Tìm địa chỉ, sắp xếp mới/cũ, xem ảnh/bbox, độ tin cậy AI, tọa độ, biển ở gần; duyệt/từ chối                 |
| Báo cáo cộng đồng  | Gom báo cáo theo biển; xem lý do, ảnh, tọa độ; sửa, gỡ hoặc từ chối                                                           |
| Lịch sử xử lý      | Lọc theo biển, người thực hiện, hành động và ngày; xem dữ liệu trước và sau |
| Quản lý người dùng | Tìm theo email, đổi vai trò, khóa và mở khóa tài khoản |
| Người đóng góp     | Tìm tên đăng nhập, sắp xếp theo số đã duyệt/chờ duyệt, thống kê, lịch sử có phân trang; xử lý/xóa từng biển |

Tìm kiếm demo hỗ trợ tiếng Việt không dấu. Chế độ API gửi bộ lọc tới backend; cách tìm không dấu phụ thuộc backend/collation. CSV chỉ chứa các dòng của trang hiện tại, có BOM tiếng Việt và xử lý chuỗi giống công thức bảng tính.

## Kết nối backend walacker/traffic-sign

1. Khởi chạy các dịch vụ backend cùng MySQL, Redis và MinIO theo repo gốc. Cần tài khoản **quản trị được cấp sẵn**.
2. Tạo cấu hình:

   ```powershell
   Copy-Item .env.example .env
   ```

3. Trong `.env`, đổi thành:

   ```dotenv
   VITE_DATA_MODE=api
   VITE_API_CONTRACT=legacy
   AUTH_TARGET=http://localhost:8000
   SIGN_TARGET=http://localhost:12000
   RTTS_TARGET=http://localhost:11000
   VITE_AUTH_BASE=/api/auth
   VITE_SIGN_BASE=/api/signs
   VITE_RTTS_BASE=/api/rtts
   ```

4. Khởi động lại `npm run dev`, đăng nhập bằng tài khoản admin của hệ thống.

Vite proxy chuyển request sang ba dịch vụ. Không cần gọi trực tiếp các cổng backend từ trình duyệt. Khi backend bị lỗi, giao diện báo lỗi và cho thử lại; **không tự chuyển sang dữ liệu mẫu**. HTTP 401/403 hoặc thông báo phiên không hợp lệ sẽ đưa về màn hình đăng nhập.

MD5 được dùng cho trường mật khẩu đăng nhập để tương thích backend hiện tại; đây không phải lớp mã hóa đường truyền. Khi triển khai thực tế, dùng HTTPS cho điểm truy cập web/API. Không lưu mật khẩu trong trình duyệt.

## API đã ánh xạ

Các request đều là POST JSON. Các request sau đăng nhập có `sessionId`.

| Dịch vụ              | Endpoint                                                                                                                       |
| -------------------- | ------------------------------------------------------------------------------------------------------------------------------ |
| user-service         | `/authen/v1.0/do-login-admin`, `/authen/v1.0/do-logout`, `/user/v1.0/get-cust-info`                                            |
| traffic-sign-service | `/sign-type/v1.0/get-sign-types-list`, `/traffic-sign/v1.0/get-traffic-sign-by-page`, `/traffic-sign/v1.0/update-traffic-sign` |
| rtts-service         | `/rtts/v1.0/get-list-rtts-not-verify`, `/rtts/v1.0/get-list-rtts-verified`, `/rtts/v1.0/approve-rtts`                          |
| rtts-service         | `/rtts/v1.0/get-list-report-rtts`, `/rtts/v1.0/approve-report-rtts`                                                            |
| rtts-service         | `/rtts/v1.0/get-list-user-upload-rtts`, `/rtts/v1.0/get-cust-upload-his`, `/rtts/v1.0/delete-rtts`                             |

Những điểm cần giữ khi sửa mã:

- Danh mục trả `data.productList`, `totalProducts`; các danh sách RTTS trả `data.data`, `totalRecords`.
- API chờ duyệt cần `sortBy: created_at` và `sortDir: asc/desc` viết thường.
- API thống kê cần `sortBy: PENDING/APPROVAL` và `sortDir: DESC` viết hoa.
- API xử lý báo cáo nhận **ID bản ghi báo cáo** trong trường `rttsId`, theo cách backend hiện tại tra cứu báo cáo.
- Chấp nhận báo cáo chuyển biển liên quan sang trạng thái đã xóa theo logic backend, nên giao diện có bước xác nhận rõ ràng.
- API danh mục hiện chỉ cho cập nhật **tên**. Không tự thêm chức năng tạo/xóa danh mục hay sửa mã/ảnh khi backend chưa hỗ trợ.
- Chế độ API lấy danh sách biển trùng từ backend. Demo minh họa phép tìm cùng mã, đã duyệt và trong khoảng 150m; bán kính này không thay đổi thuật toán backend.

## Chạy Docker với Signa

Dockerfile mặc định build VisionMap ở chế độ API Signa. Nginx phục vụ giao diện ở cổng 3000 và chuyển nguyên đường dẫn, query và yêu cầu của trình duyệt từ `/api/v1` và `/icons/` đến gateway Signa cùng origin.

```powershell
docker build -t visionmap-admin:signa .
docker run --rm --network signa_default -p 127.0.0.1:3000:3000 -e SIGNA_TARGET=http://nginx:80 visionmap-admin:signa
```

Thay `signa_default` bằng mạng Compose của nhóm. `SIGNA_TARGET` mặc định là `http://nginx:80`. Giá trị này là địa chỉ gateway Signa mà container truy cập được, chỉ gồm giao thức, host và cổng. Gateway phải phục vụ cả API và ảnh MinIO theo thiết kế. Nếu gateway chạy trên máy Windows ở cổng 8080, có thể dùng `http://host.docker.internal:8080` trên mạng Docker do người dùng tạo.

Mở <http://localhost:3000> và đăng nhập bằng tài khoản Signa. Các biến `VITE_*` được đóng vào bundle khi build. Có thể đổi `SIGNA_TARGET` lúc tạo container mà không cần build lại web.

### Docker với backend walacker/traffic-sign

Repo cũ có thể vẫn dùng đường dẫn `services/admin-web-service`. Chọn cấu hình legacy lúc build để giữ proxy của ba service cũ:

```powershell
docker compose build --build-arg VITE_API_CONTRACT=legacy admin-web-service
docker compose up -d --no-build admin-web-service
```

`npm run preview` chỉ phục vụ file tĩnh. Để kiểm tra API với backend, dùng `npm run dev` hoặc container Nginx.

## Build và kiểm tra

```powershell
npm test
npm run build
npm run preview
```

- Kiểm thử logic: tìm kiếm, phân trang, bbox/ảnh không hợp lệ, CSV, chuyển trạng thái, số liệu người dùng, báo cáo và lưu dữ liệu.
- Kiểm thử adapter API bằng mock: payload sắp xếp, ID báo cáo, MD5 tương thích, phân trang, lỗi nghiệp vụ và phiên hết hạn.
- Đã thử giao diện máy tính và điện thoại: đăng nhập demo, tìm không dấu, sửa tên, duyệt, xử lý báo cáo và kiểm tra tác động trong lịch sử người dùng.
- Chưa chạy đầu cuối với backend/DB thật và chưa chạy Docker trong môi trường bàn giao này.
- Thư mục `dist` trong ZIP là bản demo, tạo bằng `npm run build`. Dockerfile build lại ứng dụng ở chế độ API Signa.

Font Be Vietnam Pro được tải từ Google Fonts nếu có mạng; có Segoe UI/sans-serif dự phòng. Có thể tự lưu font vào `public` nếu cần hoàn toàn offline.

## Cấu trúc mã nguồn

```text
src/
  App.tsx                 Phiên đăng nhập, điều hướng, khung trang, hỗ trợ
  api.ts                  Adapter backend và chọn chế độ demo/API
  components.tsx          Modal có quản lý focus, phân trang, ảnh/bbox, trạng thái
  demo.ts                 Dữ liệu mẫu và chuyển trạng thái
  domain.ts               Hàm xử lý dữ liệu dùng chung
  types.ts                Kiểu dữ liệu
  styles.css              Giao diện responsive
  pages/
    Overview.tsx          Tổng quan
    Catalog.tsx           Danh mục và loại biển
    Reviews.tsx           Chờ duyệt, báo cáo, chi tiết đăng tải
    Contributors.tsx      Thống kê và lịch sử người dùng
tests/
  domain.test.ts
  api.test.ts
```

Tham khảo phạm vi: [tài liệu đồ án](https://docs.google.com/document/d/1mldo6xSVUdWwiu3gUib2IxnyfQkT5qxbnFjh1Bff6sc/edit), phần website quản trị và các màn hình trong Chương 5.
