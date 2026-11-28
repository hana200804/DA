# Geo

`ObservationGeoMapper.kt` chấp nhận fix GPS chính xác tới 15 m và tối đa 2 giây trước lúc analyzer nhận khung. Hướng làm tròn theo độ và chỉ dùng khi có bearing và tốc độ từ 5 km/h.

`data/location` cung cấp fix qua Fused Location Provider khi có quyền vị trí chính xác. Phiên camera thu GPS ở foreground khi tài khoản đã đăng nhập và bật đóng góp. Đề xuất `CAMERA` dùng chung hàng đợi Room, clientId UUID và chính sách mạng từ luồng gửi ảnh. Tọa độ là vị trí xe ở khung giữ ảnh cắt; độ lệch tới biển cần đo thực địa.
