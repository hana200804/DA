# Analyzer

| Tệp | Trách nhiệm |
| --- | --- |
| `CameraFrameAnalyzer.kt` | Nhận RGBA từ CameraX, lấy ngữ cảnh ở đầu khung, xoay bitmap theo camera và luôn đóng ImageProxy. Khi máy báo nhiệt từ MODERATE trở lên, bỏ cách một khung (API 29+). |
| `FramePreprocessor.kt` | Letterbox bilinear theo tâm pixel, làm tròn về số chẵn, đệm 114 và chuẩn hóa RGB thành float32 NHWC. |
| `VisionPipeline.kt` | Điều phối tiền xử lý, detector, NMS và tracker theo thứ tự khung trên một luồng. Tính FPS và thời gian từng giai đoạn. |

CameraX chuyển YUV sang RGBA trước khi trao khung cho analyzer. Pipeline nhận bitmap RGB đã đúng chiều, dùng chung cho camera và phát lại video. Khi track có GPS hợp lệ, pipeline giữ JPEG vùng biển, nới box 10% mỗi cạnh, cạnh dài tối đa 256 px và chất lượng 85.
