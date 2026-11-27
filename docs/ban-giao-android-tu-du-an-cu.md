# Bàn giao Android từ dự án cũ

Ngày cập nhật: 2026-11-26. Người dùng đã chuyển code sang dự án này và yêu cầu tiếp tục làm việc tại đây.

## Thư mục làm việc và nguồn thiết kế

- Dự án hiện tại: `D:/PTUD/DA`; Android: `mobile/`; backend: `backend/`; AI: `ai/`.
- Dự án cũ để đối chiếu: `D:/DoAn`. Không tiếp tục sửa bản cũ cho công việc mới, không chép đè code ở đây bằng bản cũ.
- Đọc `AGENTS.md`, `CLAUDE.md`, `docs/README.md`, đặc tả hiện hành và ADR trước khi tích hợp. Kế hoạch cũ trong cuộc trò chuyện không thay thế thiết kế mới.
- Đây là tài liệu chuyển ngữ cảnh, không phải biên bản nghiệm thu dự án mới. Lượt bàn giao không sửa code chức năng, không chuyển key, không commit hoặc push.

## Khác biệt phạm vi cần giữ

`CLAUDE.md` hiện tại yêu cầu so sánh YOLOv5, YOLOv8 và DETR; camera điện thoại dùng một YOLO nhỏ TFLite. Không mặc định tiếp tục detector + classifier hai bước của kế hoạch cũ nếu khác hợp đồng mô hình mới.

Thiết kế mới cắt dẫn đường khỏi phạm vi chính; cảnh báo tốc độ giọng nói là P2. Code VietMap tìm địa chỉ/tuyến ở bản cũ là khả năng đã triển khai, không tự động trở thành yêu cầu P0 ở đây. Không tự triển khai tiếp WarningEngine/TTS chỉ vì đó là bước tiếp theo của cuộc trò chuyện cũ.

Backend theo thiết kế Spring Boot, MySQL, Redis và MinIO; dịch vụ AI Python/FastAPI. Không coi backend/AI là chưa có: repo mới đã chứa mã, cấu hình và kiểm thử, cần kiểm tra thực trạng trước khi nối Android.

## Các phần đã thực hiện ở bản cũ

| Phần | Tiến độ ở `D:/DoAn` |
| --- | --- |
| Khung Android | Kotlin/Compose, MVVM, Hilt, Coroutines/Flow; tách ui, domain, data, vision |
| Xác thực | Khung riêng đăng nhập/đăng ký/OTP/reset/change password; chế độ khách; fake auth lưu được phiên; client API đã có nhưng chưa nghiệm thu backend thật |
| Lưu trữ | Room schema 2, migration 1 → 2 cho tọa độ biển; DataStore cài đặt; Android Keystore bảo vệ thông tin phiên |
| Bản đồ | VietMap thật, quyền vị trí, biển mẫu trong Room, tìm địa chỉ/điểm đến/tuyến và khoảng cách/thời gian qua API thật |
| Camera | CameraX preview thật, quyền camera, tạm dừng/tiếp tục, rotation, nhả tài nguyên khi xuống nền/rời màn hình |
| Dọn giao diện | Bỏ route hồ sơ mẫu; bản thiết kế sang src/debug; vô hiệu hóa nút chưa có dịch vụ với giải thích rõ; thống kê đóng góp chưa có thì rỗng |
| GPS lái xe | Flow liên tục riêng với repository/ViewModel, m/s → km/h, lọc fix cũ/thiếu chính xác/gần đúng, hủy theo lifecycle |

Android không kết nối trực tiếp Redis. Khi tích hợp xác thực thật: gọi backend, lưu thông tin xác thực an toàn, gửi kèm request; backend quản lý phiên Redis/TTL/thu hồi. Cần đối chiếu hợp đồng thực tế ở repo mới, không tự suy đoán refresh token hoặc kiểu session.

`CameraPreview`/CameraX PreviewView là hình trực tiếp của camera, khác Compose `@Preview` và dữ liệu thiết kế. Xử lý camera/AI không đặt trong Composable. GPS thực tế ở data/location; nghiệp vụ cảnh báo nếu được chọn nằm ngoài UI/vision.

## Đối chiếu bản đã chuyển tại thời điểm bàn giao

- `VietMapDirectionsRepository.kt` và `mobile/MAP_SETUP.md` trùng bản cũ theo nội dung file.
- Có `ui/drive/ActiveDriveRoute.kt` và `vision/camera/CameraPreviewSession.kt`, nhưng nội dung khác bản cũ. ActiveDriveRoute đang đọc trong lượt này chỉ có camera, chưa nối luồng tốc độ GPS đã làm ở bản cũ.
- Chưa thấy hai file `ui/drive/DriveViewModel.kt` và `data/location/FusedDriveLocationRepository.kt` tại các đường dẫn tương ứng. Không kết luận đã chuyển đầy đủ mốc GPS.
- `mobile/CAMERA_SETUP.md` và `mobile/ARCHITECTURE.md` cũng khác bản cũ. Không thay nguyên file, vì dự án mới có thể đã được chỉnh độc lập.
- `mobile/local.properties` tồn tại; không đọc/chép/in giá trị key trong lượt bàn giao.

Các file GPS cũ cần đối chiếu nếu quyết định giữ tính năng này:

```text
mobile/app/src/main/java/com/signa/app/
├── domain/model/DriveLocation.kt
├── domain/repository/DriveLocationRepository.kt
├── data/location/FusedDriveLocationRepository.kt
├── ui/drive/DriveViewModel.kt
├── ui/drive/ActiveDriveRoute.kt
├── ui/drive/ActiveDriveScreen.kt
└── di/LocationModule.kt
```

Còn có strings và test liên quan trong domain/model, ui/drive. Nếu cần đưa sang, chọn thay đổi tương ứng và kiểm tra xung đột; không copy toàn bộ mobile hoặc file cấu hình riêng.

## Bằng chứng kiểm thử lịch sử

- Bản cũ ở mốc cuối: build debug thành công; 60 unit test đạt; lint không có lỗi.
- Android `com.signa.app.ui.drive.DriveGpsIntegrationTest` đã qua trên emulator: Fused Location với mock provider 10 m/s → UI 36 km/h; tạm dừng xóa tốc độ; tiếp tục với 20 m/s → UI 72 km/h. Đây là dữ liệu kiểm thử, không phải đo thực địa.
- Test camera lifecycle đã qua ở mốc camera trước đó. Các test lưu trữ 13 ca được chạy ở mốc trước, không đồng nghĩa chạy lại ở mỗi thay đổi.
- Chưa đo camera/GPS/hiệu năng trên điện thoại thật. Chưa có nghiệm thu Android ↔ backend ↔ AI thật trong cuộc trò chuyện cũ.
- Không gán các kết quả trên cho `D:/PTUD/DA`: phải build và kiểm thử lại chính checkout hiện tại.

Toolchain Android cũ dùng Oracle JDK 25 đầy đủ, SDK 37, minSdk 26. Backend mới ghi JDK 21; không dùng chung giả định JDK cho hai module. Đọc Gradle/toolchain của từng module trước khi chạy.

## Việc tiếp theo tại dự án mới

1. Kiểm tra trạng thái Git và thay đổi đang có, đọc hợp đồng API/nhận diện hiện hành; không làm mất code của nhóm.
2. Đối chiếu Android mới với phần đã chuyển, xác định rõ phần nào thuộc P0 và phần nào chỉ giữ như mở rộng. Nếu cần GPS thì nhập có chọn lọc những thay đổi thiếu, sau đó nghiệm thu lại.
3. Nối tài khoản với identity backend: request/response, phiên Redis, hết hạn/đăng xuất, lỗi mạng và 401. Không tạo phiên Redis ở Android.
4. Nối danh mục/biển theo vùng và cache Room theo API backend hiện có; giữ đánh dấu dữ liệu mẫu cho tới khi nhận dữ liệu thật.
5. Đối chiếu hợp đồng mô hình điện thoại với AI: labels/version, input shape, letterbox/normalization, output/NMS, tọa độ box và test fixtures. Sau đó nối ImageAnalysis/TFLite và kiểm chứng trên thiết bị.
6. Nối gửi đề xuất/ảnh và trạng thái phê duyệt theo đặc tả mới. Cảnh báo/TTS chỉ làm khi được chọn trong phạm vi mở rộng.

## Cách phối hợp đã thống nhất

- Giải thích bằng tiếng Việt, làm từng mốc có đầu ra kiểm chứng; tránh dựng thêm nhiều lớp hoặc thư mục rỗng.
- Phân biệt rõ dữ liệu thật, fake repository và mẫu thiết kế; không báo tích hợp thật chỉ vì client/khung đã có.
- Không in key/token/mật khẩu, không chép local.properties, .env hoặc kho dữ liệu tài khoản sang tài liệu.
- Ưu tiên checkout hiện tại. Không tự push, triển khai hoặc thay đổi phạm vi thiết kế.
