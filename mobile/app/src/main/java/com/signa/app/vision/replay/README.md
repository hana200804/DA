# Phát lại video

`VideoReplay.kt` lấy từng khung theo chỉ số từ video cục bộ qua MediaMetadataRetriever và chạy `VisionPipeline` dùng chung với camera. Chọn video MP4 có số khung trong metadata trên màn **Lái xe → Phát lại video** ở bản debug, Android 9 trở lên.

Màn hình chiếu box lên ảnh và giữ khung cuối để đối chiếu. Chế độ đo chạy nhanh hết khả năng xử lý và không dùng GPS hay hàng đợi. Logcat tag `SignaReplay` ghi JSON tổng hợp FPS và p50/p95 theo giai đoạn sau khi hết khung. Số liệu lần phát gồm cả khung đầu và bỏ thời gian nạp mô hình.
