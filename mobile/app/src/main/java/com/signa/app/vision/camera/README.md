# Camera

| Tệp | Trách nhiệm |
| --- | --- |
| `CameraPreviewSession.kt` | Bind Preview và ImageAnalysis trong cùng ViewPort của camera sau với `STRATEGY_KEEP_ONLY_LATEST`. Cập nhật hướng, giữ màn hình sáng khi có hình và nhả các use case khi rời foreground. |
| `CameraPreviewStatus.kt` | Trạng thái quyền, luồng hình, lỗi camera và lỗi nhận diện. |

Adapter Compose trong `ui/drive/components/CameraPreview.kt` chiếu box từ ảnh phân tích sang PreviewView bằng biến đổi CameraX. `ActiveDriveRoute` xin quyền và điều khiển vòng đời phiên lái xe.
