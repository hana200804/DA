# Geo

Trách nhiệm: kết hợp kết quả track với vị trí thiết bị phù hợp về thời gian để chuẩn bị quan sát gửi ra ngoài vision.

| File dự kiến, chưa tạo | Nhiệm vụ |
| --- | --- |
| ObservationGeoMapper.kt | Ghép thông tin biển/track với dữ liệu vị trí đã được kiểm tra |

Đầu vào: kết quả track và vị trí/thời điểm/độ chính xác do tầng location cung cấp. Đầu ra: dữ liệu quan sát theo hợp đồng domain/backend đã chốt.

Không tự gọi FusedLocationProviderClient trong package này; nguồn GPS nằm ở data/location và được điều phối bên ngoài. Không suy ra chính xác tọa độ biển trên đường chỉ từ một box 2D và GPS xe. Không dùng tọa độ mẫu hoặc fix cũ để giả thành quan sát thật. Việc gửi và lưu quan sát thuộc repository ở tầng data.

Hiện package này chỉ giữ khung, chưa có thuật toán gắn vị trí.
