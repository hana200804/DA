# Lát 10 · Hoàn thiện backend

> Kế hoạch thực thi theo [thiết kế triển khai](../thiet-ke-trien-khai.md)
> và quy tắc làm song song. Đánh dấu `- [x]` khi xong từng bước.

Mục tiêu là triển khai Signa qua một gateway và chuẩn bị kịch bản kiểm thử
hiệu năng cho môi trường tương đương bản demo.

## Phần A · Hạ tầng và kịch bản k6

- [x] Cấu hình nginx định tuyến API, ảnh và web quản trị theo
  [Kiến trúc §8–9](../../03-kien-truc.md#8-triển-khai). Ghi đè
  `X-Forwarded-For` bằng `$remote_addr` và sinh mã request khi thiếu header.
- [x] Thêm dịch vụ nhận diện, gateway và web quản trị vào Compose. Dịch vụ
  nhận diện nằm trong mạng nội bộ, nạp ba checkpoint từ thư mục chỉ đọc
  và báo trạng thái qua `/healthz`.
- [x] Thêm cấu hình demo bật TLS, chỉ mở cổng gateway, đặt chính sách
  khởi động lại và giới hạn bộ nhớ cho máy 4 vCPU, 8 GB RAM. MySQL, Redis
  và MinIO mở cổng trên loopback ở môi trường dev để kiểm thử từ host.
- [x] Thêm ba kịch bản k6 theo [Kiểm thử §8](../../08-kiem-thu.md#8-kiểm-thử-hiệu-năng-server)
  và hướng dẫn chạy. Kiểm thử tải nhận diện gọi API nội bộ để đo cả ba
  mô hình ở tốc độ yêu cầu mà không chạm giới hạn của API công khai.
- [x] Kiểm tra Compose, nginx, kịch bản k6, Gradle và Markdown. Ghi các
  kiểm tra cần môi trường thật vào mục Tiến độ.

Gateway proxy tới `web-admin:3000` để dùng lại luồng build, fallback cho
SPA và cache tài nguyên của web quản trị. Gateway xử lý các đường dẫn
API và ảnh trước khi chuyển phần còn lại tới web quản trị. URL ảnh ký
qua gateway giữ nguyên `Host` khi chuyển tới MinIO để chữ ký còn hiệu lực.

Phạm vi thay đổi là `infra/`, kế hoạch này và các tệp mới trong
`backend/src/test/k6/`. Các lát 2, 8 và 9 có thể tiếp tục độc lập.

## Phần B · Sau khi ghép các lát

- [ ] Viết script mô phỏng đề xuất và đánh giá phê duyệt theo
  [Kiểm thử §6](../../08-kiem-thu.md#6-đánh-giá-phê-duyệt-có-trợ-giúp).
- [ ] Chạy checklist bảo mật đầy đủ theo
  [Kiểm thử §7](../../08-kiem-thu.md#7-kiểm-thử-bảo-mật) sau khi ghép
  toàn bộ lát.
- [ ] Đo k6 trên môi trường demo với dữ liệu mẫu và checkpoint thật.
  Ghi kết quả, xác nhận hàng đợi đề xuất xử lý hết tải và đối chiếu NFR.

## Tiến độ

**2026-11-24.** Kế hoạch ở commit `202de8e`. Phần A hoàn tất trên
`backend/10-hardening`. Phần B chờ tích hợp các lát.

- Compose CLI độc lập đã phân giải cấu hình gốc và các profile `migrate`,
  `app`, `dev`, `demo`. Dev chỉ bind loopback. Override demo chỉ publish
  443, mount TLS và checkpoint chỉ đọc, giới hạn tổng 6,5 GiB cho các
  dịch vụ chạy lâu dài. Recognition dùng PyTorch CPU trên mạng nội bộ.
- `nginx -t` bằng nginx 1.30.8 và thử HTTP/HTTPS với upstream mô phỏng
  đạt. Đã kiểm đường dẫn API, SPA, icon, giới hạn 12 MiB, header truy vết
  và chuỗi ký giữ nguyên khi chuyển sang đường dẫn ảnh riêng tư.
- k6 2.4.0 đọc được cả ba kịch bản. Thử ngắn với API mô phỏng đạt cho
  bbox, đăng nhập và một đề xuất multipart, cùng phản hồi nhận diện
  `200` và `503` có `Retry-After`. Gradle đạt 163 ca và Markdown đã lint.
- Trong môi trường Python 3.11, đã cài phụ thuộc nhận diện CPU, nạp 46
  nhãn, import YOLOv5 ở commit đã ghim và kiểm tra `/healthz` trả `503`
  khi thiếu checkpoint.
- Máy thiếu Docker Engine và checkpoint nhóm. Còn phải build và chạy
  các container cùng nhau, kiểm chữ ký với MinIO thật và xác nhận mức
  RAM khi nạp ba mô hình trên máy demo. Kết quả tải và bảo mật đầy đủ
  thuộc phần B.
