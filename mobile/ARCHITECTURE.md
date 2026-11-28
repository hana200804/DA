# Kiến trúc Android Signa

Ứng dụng dùng một module `app`, MVVM + Clean Architecture, Hilt và Coroutines/Flow.
Mặc định hỗ trợ backend giả lập để phát triển độc lập; cấu hình local hiện có thể chọn API thật. Tài khoản đã được kiểm chứng với MySQL/Redis qua backend. Camera chạy nhận diện YOLOv8n trên thiết bị; đề xuất camera và ảnh dùng chung hàng đợi đồng bộ.

## Cấu trúc hiện tại

```text
app/src/main/java/com/signa/app/
├── MainActivity.kt
├── SignaApplication.kt
├── ui/                         # Screen, Route, ViewModel, UiState theo tính năng
│   ├── auth/                   # Form và thao tác xác thực
│   ├── account/                # Khách, hồ sơ thật, cập nhật, đăng xuất
│   ├── drive/                  # Camera lái xe, xin quyền qua Route
│   ├── map/                    # Biển cache, bản đồ và tìm tuyến
│   ├── sign/                   # Tra cứu/chi tiết biển
│   ├── contributions/          # Thống kê từ repository
│   ├── settings/               # Cài đặt
│   ├── navigation/             # Graph xác thực, graph chính, tab
│   ├── components/             # UI dùng chung
│   └── theme/                  # Màu, kiểu chữ, hình dạng
├── domain/
│   ├── model/                  # Model Kotlin, không phụ thuộc UI/Retrofit/Room
│   └── repository/             # Interface nghiệp vụ
├── data/
│   ├── remote/
│   │   ├── backend/
│   │   │   ├── api/            # Retrofit AuthApi và các API backend bổ sung sau
│   │   │   ├── dto/            # Request/response backend
│   │   │   ├── client/         # ApiClientFactory, ApiConfig
│   │   │   ├── interceptor/    # Bearer, request ID, xử lý 401
│   │   │   └── error/          # apiCall và ApiException
│   │   └── vietmap/            # Client/API/DTO/polyline riêng của VietMap
│   ├── local/
│   │   ├── database/           # Room, migration, dao/, entity/
│   │   ├── preferences/        # DataStore
│   │   └── session/            # Phiên mã hóa, Keystore và lưu/khôi phục
│   ├── location/               # Dịch vụ vị trí Android
│   ├── mapper/                 # Entity ↔ domain model
│   ├── repository/             # Triển khai hợp đồng domain
│   └── fake/                   # Nguồn giả lập, lựa chọn qua DI
├── vision/
│   ├── camera/                 # CameraX, lifecycle và trạng thái thiết bị
│   ├── analyzer/               # Camera analyzer, letterbox và pipeline
│   ├── detector/               # LiteRT, giải mã và NMS
│   ├── classifier/             # Khung đã chuẩn bị; chỉ dùng nếu pipeline cần
│   ├── tracker/                # Theo dõi và bỏ phiếu nhãn
│   └── geo/                    # Khung gắn vị trí cho quan sát
└── di/                         # Hilt: network, storage, repository, GPS, VietMap

app/src/debug/java/com/signa/app/preview/
├── PreviewSamples.kt           # Dữ liệu thiết kế, chỉ build debug
└── ScreenPreviews.kt           # Compose @Preview, không có route runtime

app/src/test/                   # JVM test, HTTP giả lập và E2E khi được cấu hình
app/src/androidTest/            # Room, DataStore và camera trên Android
```

Chỉ tạo use case khi có nghiệp vụ cần phối hợp; không tạo lớp chỉ để chuyển tiếp một lời gọi repository. Giữ các thư mục vision đã chuẩn bị để triển khai giai đoạn sau; .gitkeep chỉ giữ thư mục trong Git, không phải mã xử lý. Camera nằm trong `vision/camera`, GPS nằm trong `data/location`; AI sẽ bổ sung theo hợp đồng mô hình hiện hành, không mặc định cần classifier riêng.

## Luồng phụ thuộc

```text
Screen ← UiState ← ViewModel → domain.repository
                                   ↑
                          data.repository / data.fake
                                   ↓
                         Retrofit / Room / DataStore
```

- Screen nhận state và callback; Route thu thập Flow theo lifecycle, nối điều hướng.
- ViewModel gọi interface, xử lý tải/lỗi và sự kiện kết quả; không gọi DAO hoặc Retrofit.
- Domain không phụ thuộc Android, Compose, Room, Retrofit hoặc Hilt.
- DTO/entity và ánh xạ thuộc `data`; Hilt lắp ghép các dependency tại `di`.
- Component riêng đặt cạnh tính năng; component dùng chung đặt trong `ui/components`.
- `src/debug/java/com/signa/app/preview/PreviewSamples.kt` chuyển dữ liệu `FakeData` thành UiState cho Preview.
  Preview chỉ giúp xem thiết kế; dữ liệu giả lập chạy trong app nằm ở `data/fake`.

## Khung xác thực và khung chính

`SignaAppViewModel` quan sát phiên và cung cấp `Loading`, `SignedOut`, `Guest`,
`Authenticated`. Khi đang đọc phiên lưu, app hiển thị tải thay vì mở tab tạm thời.

- `AuthNavHost`: Đăng nhập, Đăng ký, Quên mật khẩu, OTP/Đặt lại; không có bottom bar.
- `SignaNavHost`: các tab chính, chi tiết và Đổi mật khẩu trong tài khoản.
- Hai graph có NavController riêng. Khi đổi quyền truy cập, graph cũ bị gỡ bỏ,
  bao gồm back stack và ViewModel của các màn trong graph đó.
- Đăng ký thành công trở về Đăng nhập; chỉ phiên hợp lệ hoặc lựa chọn khách rõ ràng
  mới mở các tab. Khách không được tự khôi phục khi app khởi động lạnh.

## Nguồn dữ liệu

| Hợp đồng | Bản triển khai đang được cung cấp | Hành vi |
| --- | --- | --- |
| SettingsRepository | SettingsRepositoryImpl | DataStore: loại xe, giọng nói, đóng góp, chỉ Wi-Fi |
| AuthRepository | FakeAuthRepository (mặc định) / RemoteAuthRepository | Cùng hợp đồng đăng ký, đăng nhập, đăng xuất, OTP, đặt lại/đổi mật khẩu |
| SignRepository | CachedSignRepository | Đọc Room bằng Flow; nạp biển mẫu khi cache trống |
| ContributionRepository | FakeContributionRepository | Thống kê và quan sát minh họa |

Room `signa.db` có `traffic_signs`, `fake_accounts`, `password_resets` và
`current_session`. Schema 1 và 2 được xuất tại `app/schemas/`. Migration 1 → 2 thêm tọa độ nullable, giữ dữ liệu đã lưu. Chưa có
cache biển theo vùng hoặc hàng đợi upload; phần đó nối tiếp khi làm bản đồ và gửi quan sát.

Camera dùng CameraX thật ở màn lái xe đang hoạt động; hình minh họa chỉ ở trang chuẩn bị. Bản đồ đã dùng VietMap SDK, GeoJSON, tìm địa chỉ/tuyến và định vị một lần qua LocationRepository; khi thiếu key chỉ có nền trống/marker mẫu, chưa có đường phố. Tìm kiếm lọc biển đã lưu theo tên, mã, vị trí;
chi tiết nhận `signId` ổn định và có trạng thái không tìm thấy. Cache mẫu chỉ phục vụ
kiểm thử khung ứng dụng, không dùng làm dữ liệu cảnh báo ngoài thực tế.

## Tài khoản và phiên

1. Mở app chưa có phiên sẽ vào Đăng nhập, không có bottom bar. Chọn Đăng ký và nhập email, tên, mật khẩu từ 8 ký tự.
2. Đăng ký thành công trở về đăng nhập; email trùng và sai mật khẩu có lỗi riêng.
3. Đăng nhập thành công mở khung các tab, bắt đầu ở Bản đồ. Đóng/mở app sẽ kiểm tra và khôi phục phiên Room hợp lệ trước khi hiển thị khung chính.
4. Quên mật khẩu → Gửi OTP → đọc **mã thử nghiệm** trên màn hình → nhập mã và mật khẩu mới.
5. Đổi mật khẩu cần mật khẩu hiện tại. Backend giữ phiên hiện tại khi đổi thành công
   và thu hồi các phiên khác. Đặt lại mật khẩu hoặc đổi trong chế độ fake yêu cầu đăng nhập lại.
6. Đăng xuất hoặc hết hạn phiên sẽ xóa khung chính và trở về Đăng nhập; nút Back không quay lại các tab.
7. Chế độ khách chỉ mở khi người dùng chủ động chọn “Tiếp tục với khách”. Từ Tài khoản → Đăng nhập sẽ trở lại khung xác thực.

Fake auth lưu tài khoản cục bộ, mật khẩu/OTP dưới dạng PBKDF2-HMAC-SHA256 có salt
(120.000 vòng). OTP có 6 chữ số, sống 5 phút, tối đa 5 lần nhập sai, gửi lại sau 30 giây;
mã dùng thành công bị hủy. Mã thử nghiệm chỉ hiển thị trong chế độ fake, không gửi email.

Phiên fake sống 24 giờ. Phiên API dùng `expiresAt` từ server. Nội dung phiên được
mã hóa AES-GCM bằng khóa Android Keystore, dùng IV mới mỗi lần ghi. Database bị loại
khỏi Android backup vì khóa không chuyển sang thiết bị khác. Không lưu mật khẩu thô.
Hết hạn, dữ liệu mã hóa hỏng, đổi chế độ fake/API hoặc 401 của request đã xác thực
đều hủy phiên và chuyển về khung Đăng nhập. Xóa phiên theo token cũ không xóa nhầm lần đăng nhập mới.
Ứng dụng kiểm tra hết hạn cả khi đang mở và khi trở lại foreground.

Cài đặt mặc định: ô tô con, giọng nói bật, đóng góp tắt, chỉ Wi-Fi bật. DataStore
cập nhật từng trường và có trạng thái tải/lỗi/thử lại; enum lạ trở về ô tô con.

## Giao diện tài khoản theo mẫu

`AuthScreen` bố trí form riêng theo từng mode. `AuthInputField` dùng nhãn ngoài,
placeholder, icon và ẩn/hiện mật khẩu. `OtpInput` hiển thị 6 ô nhưng dùng một
BasicTextField để hỗ trợ dán mã, xóa và đọc bằng trợ năng. Gửi lại mã gọi
AuthViewModel → AuthRepository; chỉ xóa mã đang nhập khi gửi lại thành công.

`AccountViewModel` kết hợp phiên với SettingsRepository để hiển thị loại xe đã lưu.
ProfileScreen nhận dữ liệu/callback và quản lý hộp thoại thông tin cá nhân
và trợ giúp. Với backend thật, mở thông tin cá nhân tải hồ sơ qua `GET /me`
và lưu tên hiển thị qua `PATCH /me`. Loại xe lấy từ cài đặt cục bộ.
ContributionSummary chứa các quan sát có biển, trạng thái và thời điểm; UI
ánh xạ thành nhãn trạng thái và ngày, không tự gán kết quả xác minh.
FakeData cung cấp các quan sát minh họa từ danh mục biển mẫu hiện có.

## Cấu hình API

Chạy mặc định không cần backend. Để build chế độ API:

```powershell
.\gradlew.bat :app:assembleDebug -Psigna.useFakeBackend=false -Psigna.apiBaseUrl=http://10.0.2.2:8080/api/v1/
```

URL lấy theo thứ tự: `-Psigna.apiBaseUrl`, biến môi trường `SIGNA_API_BASE_URL`,
`API_BASE_URL` trong `local.properties`, rồi mặc định emulator ở trên.
URL chỉ có host được bổ sung `/api/v1/`; URL đã có path được chuẩn hóa dấu `/` cuối.
Chế độ fake/API là cấu hình lúc build: cờ `-Psigna.useFakeBackend` ưu tiên, sau đó `USE_FAKE_BACKEND` trong local.properties, mặc định true nếu cả hai đều thiếu. Cấu hình local hiện dùng backend thật.
Debug cho HTTP để kiểm thử nội bộ; release yêu cầu HTTPS.

AuthApi gọi các endpoint tài khoản theo `docs/07-api.md`. Đăng nhập gửi
`client: "MOBILE"` và đọc `token`, `expiresAt`, `user`. Hồ sơ đọc và cập nhật qua
`/me`. Đặt lại mật khẩu gửi `email/code/newPassword`; đổi qua `/me/password`
gửi `currentPassword/newPassword`.
OkHttp chỉ gắn Bearer cho API cùng origin/path và phiên REMOTE hợp lệ, không gắn cho
endpoint công khai hoặc upload ngoài origin. Lỗi API được ánh xạ theo `code`
và ngữ cảnh thao tác, với mã HTTP dự phòng. Khi bị giới hạn tần suất, giao diện
hiển thị số giây từ `Retry-After` nếu có. Không ghi log token, mật khẩu hoặc OTP.

API tài khoản được kiểm thử với MockWebServer bằng mẫu phản hồi từ backend Signa.
`RemoteAuthRepositoryE2eTest` chạy luồng tài khoản JVM với backend dev khi đặt
`SIGNA_E2E_BASE_URL` (ví dụ `http://localhost:8080/api/v1/`) trước khi chạy
`testDebugUnitTest --rerun-tasks`. Test tạo một tài khoản riêng cho mỗi lần chạy.

## Kiểm tra

```powershell
.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
.\gradlew.bat :app:connectedDebugAndroidTest
```

Unit test kiểm tra validation, ViewModel, băm mật khẩu, vòng đời phiên và HTTP giả lập
(serialize DTO, lỗi, timeout, header, 401 và chống xóa phiên mới).
Instrumented test kiểm tra Room trên file thật, Android Keystore, mở lại phiên/cache,
đăng ký trùng, OTP sai/hết hạn/gửi lại, đặt lại/đổi mật khẩu, đăng xuất và DataStore.
Kiểm thử lưu trữ chạy trên Android để sử dụng đúng hành vi filesystem của nền tảng.

Mốc bản đồ đầu tiên và phần cần key/API theo vùng được ghi riêng tại [MAP_SETUP.md](MAP_SETUP.md).

## Camera thật

`vision/camera/CameraPreviewSession` sở hữu CameraX Preview và nhả tài nguyên theo
vòng đời màn hình. `ui/drive/ActiveDriveRoute` xin quyền và điều khiển tạm dừng;
`ActiveDriveScreen` hiển thị trạng thái và lớp phủ kết quả từ ImageAnalysis.
Thiết kế và hướng dẫn kiểm tra nằm trong [CAMERA_SETUP.md](CAMERA_SETUP.md).

## Quy tắc tổ chức và nguồn dữ liệu

- Screen chỉ hiển thị và phát callback. Route nối state, điều hướng và quyền Android. ViewModel gọi hợp đồng domain, không giữ Activity/View/camera session.
- Domain không import Compose, Android framework, Retrofit hoặc Room. Data chịu trách nhiệm API, lưu trữ và mapper. Không đưa DTO backend hoặc Room entity thành state trực tiếp của Screen.
- Backend và VietMap dùng client riêng; không gắn token tài khoản vào request VietMap.
- Các màn thiết kế nằm trong src/debug, không được tham chiếu từ navigation hoặc main. Khách có giao diện riêng; hồ sơ đăng nhập lấy từ phiên, giữ các thao tác làm mới/sửa tên/đổi mật khẩu thật.
- Tài khoản chọn RemoteAuthRepository khi USE_FAKE_BACKEND=false. Cache biển và đóng góp vẫn có dữ liệu mẫu; refactor package không đồng nghĩa đã nối mọi API.
- Không thay schema Room, không xóa tài khoản local và không di chuyển key/token trong lần tổ chức lại này.

Cấu hình và kiểm chứng tài khoản thật xem [hướng dẫn Android local](../infra/README.md#chạy-tài-khoản-android-qua-usb-trên-windows). Các kết quả tuần 2 được ghi theo mốc lịch sử trong WEEK2_ACCEPTANCE.md, không thay thế kiểm thử checkout mới.

## Kiểm chứng sau khi tổ chức lại package

- 151 file Kotlin trong main/debug/test/androidTest có package khớp đường dẫn; domain không import Android, UI, Retrofit hoặc data.
- Build debug và compile Kotlin release thành công; release không có lớp PreviewSamples/ScreenPreviews.
- 54 kiểm thử đạt, không bỏ qua: gồm 53 unit test và 1 E2E qua backend local thật. E2E kiểm tra đăng ký, đăng nhập, lấy/sửa hồ sơ, đổi mật khẩu giữ phiên, đăng xuất và đăng nhập bằng mật khẩu mới.
- Lint Android không có lỗi; Markdown được kiểm tra theo lệnh của repo.
- Không chạy lại kiểm thử UI trên điện thoại trong lần đổi cấu trúc này. APK debug mới cần cài hoặc chạy lại từ Android Studio để thấy thay đổi giao diện khách.

## Nhận diện trên thiết bị

Preview và ImageAnalysis cùng ViewPort. Analyzer nhận khung mới nhất và chạy tiền xử lý, TFLite, NMS và tracker trên cùng luồng. Lớp phủ chiếu box sang PreviewView. Chế độ phát lại debug dùng chung pipeline.

ActiveDriveViewModel lấy vị trí từ LocationRepository. Khi track kết thúc, ViewModel dùng GPS ở khung giữ ảnh cắt và đưa đề xuất vào hàng đợi Room hiện có. [Cấu trúc vision](app/src/main/java/com/signa/app/vision/README.md)

## Tích hợp PR 59: bản đồ và danh mục

Đã merge commit d950add (PR 59, mobile/2-ban-do-danh-muc) vào nhánh Android hiện tại, sau đó áp lại các thay đổi local về cấu trúc và cấu hình. API/DTO mới được chuyển sang `data/remote/backend/api/SignaMapApi.kt` và `data/remote/backend/dto/SignaMapDtos.kt`; các import và test được cập nhật cùng thay đổi.

| Phần mới | Trách nhiệm |
| --- | --- |
| SignMapModule | Chọn remote/fake theo USE_FAKE_BACKEND, cấp Retrofit API và kho danh mục |
| RemoteSignCatalogRepository | Lấy nhóm/loại biển, ETag/304, tra cứu bằng cache khi lỗi mạng phù hợp |
| SignCatalogDatabase | Database Room riêng sign-catalog.db, schema 1, không thay schema signa.db |
| SignMapFilters | Lưu bộ lọc bản đồ bằng DataStore |
| RemoteSignMapRepository | Biển/cluster theo viewport, chi tiết, đánh giá, báo cáo và báo cáo của tôi |
| SignViewportViewModel | Quản lý vùng nhìn và bộ lọc, hủy kết quả yêu cầu cũ |
| SignCatalogViewModel/Content | Tra cứu danh mục thật và danh mục được lưu offline |
| LiveSignDetailViewModel/Content | Chi tiết biển backend, đánh giá và gửi báo cáo |
| MySignReportsViewModel/Route | Danh sách và phân trang báo cáo của tài khoản |

Nguồn đóng gói `assets/sign-catalog-v1.json` phục vụ danh mục tra cứu ban đầu, không phải bộ biển tọa độ đã được server công nhận. Offline danh mục không đồng nghĩa tải bản đồ nền offline. Repository và model mới của PR giữ thiết kế hiện có; việc đưa hợp đồng/model danh mục ra domain nếu cần là bước refactor riêng, không coi việc đổi package HTTP là hoàn tất mọi ranh giới Clean Architecture.

Hồ sơ mẫu không được đưa lại vào graph chính, preview thiết kế tiếp tục chỉ ở debug, các package vision đã chuẩn bị vẫn được giữ. Cấu hình private local và index staged trước merge được bảo toàn; bản sao stash được giữ để khôi phục khi cần.

Kiểm tra đọc trên backend local sau merge: sign-categories trả 200 với 5 nhóm, sign-types trả 200 với 46 loại, truy vấn signs trong một vùng Hà Nội trả 200 và 0 biển. Không thêm biển giả vào database để làm có marker. Bản đồ nền ở cấu hình local hiện chưa có key VietMap; cần cấu hình key riêng để xem nền đường phố.

Kiểm chứng bản ghép PR 59: build debug, compile release và lint thành công; 64 kiểm thử đạt, không bỏ qua (gồm E2E tài khoản thật và 10 ca mới về bản đồ/danh mục). Chưa cài lại hoặc kiểm thử giao diện bản ghép này trên điện thoại trong lượt tích hợp.
