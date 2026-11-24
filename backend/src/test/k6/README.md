# Kiểm thử tải backend bằng k6

Chạy trên môi trường kiểm thử đã ghép các lát có API tương ứng. Xem
[Kiểm thử §8](../../../../docs/08-kiem-thu.md#8-kiểm-thử-hiệu-năng-server)
và [cách dựng Compose](../../../../infra/README.md). Ví dụ dưới đây
dùng Docker Engine trên Linux và chạy từ gốc repo.

## Bản đồ

`map.js` chạy 50 VU, mỗi lượt lấy một vùng ngẫu nhiên bên trong
`SAMPLE_BBOX`. Mặc định dùng khu vực trung tâm Hà Nội
`105.79,21.00,105.86,21.06`. Chỉnh vùng bao theo dữ liệu mẫu đã nạp và
kiểm tra số biển trả về để đo với vùng gần 500 biển. Ngưỡng p95 dưới 300 ms.

```sh
docker run --rm --network host \
  -v "$PWD/backend/src/test/k6:/scripts:ro" \
  -e BASE_URL=http://localhost:8080 \
  grafana/k6:2.4.0 run /scripts/map.js
```

Đặt `MAP_DURATION` nếu cần đổi thời lượng mặc định một phút.

## Gửi đề xuất

`submissions.js` đăng nhập một lần với client `MOBILE`, sau đó tăng từ
5 lên 50 đề xuất/giây và giữ 50 trong một phút. Ngưỡng lỗi dưới 1%.
Chuẩn bị tài khoản thử tải có quyền `submission:create` và ảnh cắt JPEG
tối đa 200 KB. Mỗi lần lặp tạo một đề xuất mới trong hệ thống.

Đặt `SUBMISSION_EMAIL`, `SUBMISSION_PASSWORD` trong môi trường shell và
đặt `K6_FIXTURES_DIR` thành đường dẫn tuyệt đối của thư mục chứa `crop.jpg`
và `photo.jpg`.

```sh
docker run --rm --network host \
  -v "$PWD/backend/src/test/k6:/scripts:ro" \
  -v "${K6_FIXTURES_DIR:?}:/fixtures:ro" \
  -e BASE_URL -e SUBMISSION_EMAIL -e SUBMISSION_PASSWORD -e IMAGE_PATH=/fixtures/crop.jpg \
  grafana/k6:2.4.0 run /scripts/submissions.js
```

Mặc định dùng `P.123a` và mô hình `yolov8n` phiên bản `yolov8n-v1.0`.
Có thể truyền `SIGN_TYPE_CODE`, `MODEL_NAME`, `MODEL_VERSION` theo dữ
liệu thử. Sau khi chạy, theo dõi hàng đợi xử lý hết đề xuất `RECEIVED`.
Hai kịch bản gọi gateway dùng `BASE_URL` để chọn origin HTTP hoặc HTTPS.
Với HTTPS, dùng chứng chỉ mà môi trường chạy k6 tin cậy.

## Nhận diện

`recognition.js` gửi 2 yêu cầu/giây cho từng mô hình `yolov5`, `yolov8`
và `detr`, trong một phút. Kịch bản gọi thẳng API nội bộ, dùng ảnh JPEG
hợp lệ và kiểm tra readiness trước khi chạy. Phản hồi `503` kèm
`Retry-After: 1` được tính vào `recognition_backpressure`. Độ trễ p95
của phản hồi `200` dùng để đối chiếu NFR-02.

```sh
docker run --rm --network signa_recognition \
  -v "$PWD/backend/src/test/k6:/scripts:ro" \
  -v "${K6_FIXTURES_DIR:?}:/fixtures:ro" \
  -e IMAGE_PATH=/fixtures/photo.jpg \
  grafana/k6:2.4.0 run /scripts/recognition.js
```

Nếu đặt tên project Compose khác `signa`, dùng mạng
`<tên-project>_recognition`. Đặt `RECOGNITION_DURATION` để đổi thời
lượng hoặc `RECOGNITION_URL` để chọn dịch vụ nhận diện nội bộ khác.
