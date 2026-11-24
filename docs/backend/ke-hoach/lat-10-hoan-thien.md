# Lát 10 · Hoàn thiện backend

> Kế hoạch thực thi theo [thiết kế triển khai](../thiet-ke-trien-khai.md)
> và quy tắc làm song song. Đánh dấu `- [x]` khi xong từng bước.

Mục tiêu là triển khai Signa qua một gateway và chuẩn bị kịch bản kiểm thử
hiệu năng cho môi trường tương đương bản demo.

## Phần A · Hạ tầng và kịch bản k6

- [ ] Cấu hình nginx định tuyến API, ảnh và web quản trị theo
  [Kiến trúc §8–9](../../03-kien-truc.md#8-triển-khai). Ghi đè
  `X-Forwarded-For` bằng `$remote_addr` và sinh mã request khi thiếu header.
- [ ] Thêm dịch vụ nhận diện, gateway và web quản trị vào Compose. Dịch vụ
  nhận diện nằm trong mạng nội bộ, nạp ba checkpoint từ thư mục chỉ đọc
  và báo trạng thái qua `/healthz`.
- [ ] Thêm cấu hình demo bật TLS, chỉ mở cổng gateway, đặt chính sách
  khởi động lại và giới hạn bộ nhớ cho máy 4 vCPU, 8 GB RAM. Dùng override
  dev để mở cổng MySQL, Redis và MinIO trên loopback khi cần kiểm thử từ host.
- [ ] Thêm ba kịch bản k6 theo [Kiểm thử §8](../../08-kiem-thu.md#8-kiểm-thử-hiệu-năng-server)
  và hướng dẫn chạy. Kiểm thử tải nhận diện gọi API nội bộ để đo cả ba
  mô hình ở tốc độ yêu cầu mà không chạm giới hạn của API công khai.
- [ ] Kiểm tra Compose, nginx, kịch bản k6, Gradle và Markdown. Ghi các
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

**2026-11-24.** Lập kế hoạch phần A sau khi đối chiếu thiết kế, hợp đồng API
và kết quả lát 1. Phần B chờ tích hợp các lát.
