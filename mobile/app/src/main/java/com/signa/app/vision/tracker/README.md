# Tracker

Trách nhiệm: ghép các phát hiện qua nhiều frame thành cùng một biển, ổn định nhãn và xác định lúc track kết thúc.

| File dự kiến, chưa tạo | Nhiệm vụ |
| --- | --- |
| SignTracker.kt | Ghép detection, cập nhật và kết thúc track |
| TrackLabelVoter.kt | Tổng hợp nhãn/confidence qua nhiều lần thấy cùng biển |

Đầu vào: danh sách detection và thời điểm frame. Đầu ra: track đang hoạt động, biển ổn định và sự kiện track kết thúc để bước sau tạo quan sát.

Tracker không gọi API, không lưu Room, không phát TTS. Không coi một detection thoáng qua là một biển đã xác nhận. Hiện chưa có tracker được triển khai.
