# Camera lái xe

## Phạm vi

- CameraX 1.6.1 dùng camera sau cho Preview và ImageAnalysis. Mô hình YOLOv8n 640 FP16 chạy trong ứng dụng kể cả khi mất mạng.
- Chỉ xin quyền CAMERA sau khi người dùng bấm **Lái xe → Bắt đầu lái xe**. Từ chối vẫn có thể kết thúc và dùng các phần còn lại.
- Có cấp lại quyền, mở cài đặt, tạm dừng, bật lại và thử lại khi lỗi. Khi quay lại từ cài đặt, app kiểm tra lại quyền.
- Không mở camera ở tab Lái xe trước khi bắt đầu. Nhả Preview khi tạm dừng, màn hình mất RESUMED, kết thúc hoặc Composable bị hủy. Quay lại foreground thì mở lại nếu người dùng chưa tạm dừng.
- Dùng trạng thái STREAMING của PreviewView để báo có hình; không báo thành công ngay khi bind. Nếu không nhận được hình trong 15 giây, hiện lỗi để thử lại.
- Ưu tiên độ phân giải 1280 × 720, cho phép CameraX chọn kích thước gần phù hợp với thiết bị. Preview dùng FILL_CENTER, có thể cắt bớt mép ảnh để lấp khung.
- Theo dõi xoay display; Activity tái tạo sẽ tạo lại session. Giữ màn hình sáng chỉ khi Preview đang stream.
- Track xác nhận hiển thị khung, mã, tên và độ tin cậy. Màn hình có FPS phân tích và báo lỗi khi không nạp được mô hình hoặc sai SHA-256.
- Tài khoản bật đóng góp có thể cấp quyền vị trí chính xác. Track kết thúc có ảnh cắt và GPS hợp lệ được lưu vào hàng đợi đề xuất, theo cài đặt chỉ gửi qua Wi-Fi. Vị trí và hướng lấy tại khung giữ ảnh cắt.
- Các giá trị tốc độ và giới hạn chưa biết hiển thị `--`.

## Cấu trúc

```text
ui/drive/
├── DriveScreen.kt                    # Trang chuẩn bị, chưa bật camera
├── ActiveDriveRoute.kt               # Xin quyền, trạng thái thao tác, mở Settings
├── ActiveDriveScreen.kt              # Bố cục, trạng thái, nút điều khiển
├── ActiveDriveViewModel.kt           # GPS và hàng đợi đề xuất
├── VideoReplayRoute.kt               # Chọn video và hiển thị kết quả ở bản debug
└── components/                      # PreviewView và lớp phủ dùng chung

vision/camera/
├── CameraPreviewStatus.kt            # Trạng thái thiết bị
└── CameraPreviewSession.kt           # CameraX, lifecycle, rotation, nhả tài nguyên
```

Session không nằm trong ViewModel hoặc singleton Hilt vì giữ View và LifecycleOwner của màn hình. Session unbind Preview và ImageAnalysis do nó sở hữu. Callback bất đồng bộ đến sau khi đóng session không được mở lại camera.

CameraX chỉ giữ khung chờ mới nhất. Pipeline trong `vision/` xử lý trên luồng phân tích và chuyển kết quả về UI.

## Chạy và kiểm tra

```sh
cd mobile
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lint
./gradlew :app:connectedDebugAndroidTest
```

Kiểm thử thiết bị `CameraPreviewLifecycleTest` cần thiết bị/emulator có camera sau. Test kiểm tra frame STREAMING và CameraManager availability khi tạm dừng, bật lại, background, foreground và rời preview. Thiết bị không có camera sau sẽ bỏ qua test này.

Chạy riêng test sau khi build (chọn đúng thiết bị bằng `adb -s ...` nếu có nhiều thiết bị):

```powershell
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell am instrument -w -e class com.signa.app.vision.camera.CameraPreviewLifecycleTest com.signa.app.test/androidx.test.runner.AndroidJUnitRunner
```

### Kết quả tại mốc camera

- Assemble debug và APK androidTest thành công; 50 unit test đạt; lint 0 lỗi, 58 cảnh báo.
- `CameraPreviewLifecycleTest`: 1 test đạt trên emulator API 36.1 với camera sau emulated. Đã xác nhận tài nguyên camera mở/đóng qua CameraManager, không chỉ kiểm tra nhãn UI.
- Luồng UI khách: từ chối rồi cấp lại quyền; có hình camera; tạm dừng/bật lại; xoay ngang/dọc; Kết thúc/Back rồi bắt đầu phiên mới đều qua. Từ chối hai lần → mở Settings → cho phép khi dùng app → trở lại có hình.
- Không có lỗi AndroidRuntime trong phiên thử. Chưa thử camera vật lý hoặc đo hiệu năng trên điện thoại thật. Hình màu trên ảnh nghiệm thu là feed camera của emulator, không phải kết quả nhận diện AI.

Kịch bản kiểm tra thủ công:

1. Vào chế độ khách, mở Lái xe: chưa xin quyền, chưa mở camera.
2. Bắt đầu → từ chối quyền: có hướng dẫn, nút cấp quyền/cài đặt và kết thúc; không crash.
3. Cấp quyền khi dùng ứng dụng: có hình camera và FPS. Khi biển được xác nhận, kiểm tra khung, mã, tên và độ tin cậy.
4. Tạm dừng: hình được che, camera được nhả. Bật lại: có hình mới.
5. Home → mở lại app; xoay màn hình; bấm Back hoặc Kết thúc; mở lại phiên mới. Kiểm tra tài nguyên camera và không lỗi vòng đời.
6. Từ chối nhiều lần → mở Settings, cấp quyền rồi trở lại. Chế độ khách phải tiếp tục dùng được.
7. Trên điện thoại thật: thử camera đang bị ứng dụng khác chiếm, chính sách thiết bị chặn camera, màn hình khóa, chất lượng ảnh, xoay 180° và nhiệt/pin. Các trường hợp này không được coi là đã nghiệm thu chỉ qua emulator.

## Phát lại và đối chiếu

1. Cài bản debug trên Android 9 trở lên, rồi vào **Lái xe → Phát lại video**.
2. Chọn video MP4 cục bộ. Ứng dụng xử lý từng khung theo thứ tự, hiển thị khung biển đã xác nhận và giữ ảnh cuối.
3. Đọc kết quả bằng `adb logcat -d -s SignaReplay:I`. JSON ghi FPS, số khung và p50/p95 (ms) cho giải mã, tiền xử lý, suy luận, NMS, tracker và toàn khung.

Số đo mặc định tính từ khung đầu và gồm thời gian giải mã. Số đo trên emulator x86_64 phục vụ kiểm tra tích hợp. Nghiệm thu NFR-01 và NFR-06 cần thiết bị tham chiếu và video thực địa theo giao thức trong tài liệu AI. [Giao thức đo Android](../ai/README.md#đo-fps-trên-android)

Bốn ảnh fixture được đối chiếu bằng tensor thô rút gọn và inference LiteRT trực tiếp. Unit test yêu cầu SHA-256 đầu vào giống hệt, cùng mã biển, IoU ≥ 0,99 và chênh lệch độ tin cậy ≤ 0,0001. Test Android yêu cầu cùng mã, IoU ≥ 0,98 và chênh lệch ≤ 0,005 để tính đến sai số giữa runtime và CPU. [Cách sinh fixture](app/src/test/resources/vision/README.md)

## Tham khảo

- [CameraX Preview](https://developer.android.com/media/camera/camerax/preview)
- [CameraX release notes](https://developer.android.com/jetpack/androidx/releases/camera)
- [CameraState và lỗi camera](https://developer.android.com/reference/androidx/camera/core/CameraState)
