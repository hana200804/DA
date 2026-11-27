# Camera

Trách nhiệm: mở camera sau, tạo luồng hình, quản lý vòng đời, xoay và nhả tài nguyên thiết bị.

| File hiện có | Nhiệm vụ |
| --- | --- |
| CameraPreviewSession.kt | Sở hữu CameraX Preview, bind/unbind theo vòng đời và xử lý lỗi camera |
| CameraPreviewStatus.kt | Trạng thái đang mở, có hình, tạm dừng và lỗi |

Adapter Compose vẫn nằm ở ui/drive/components/CameraPreview.kt; việc xin quyền ở ActiveDriveRoute. Không chuyển form, navigation hoặc ViewModel vào package này.

Khi tích hợp nhận diện, session sẽ nối thêm luồng frame cho analyzer. Camera không trực tiếp gọi TFLite, tracker, GPS hoặc backend. Hiện chưa có luồng ImageAnalysis được nối.
