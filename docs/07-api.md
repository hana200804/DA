# 07 · API

## Mục lục

1. [Quy ước chung](#1-quy-ước-chung)
2. [Định dạng lỗi](#2-định-dạng-lỗi)
3. [Tài khoản](#3-tài-khoản)
4. [Danh mục biển](#4-danh-mục-biển)
5. [Nhận diện](#5-nhận-diện)
6. [Biển, đề xuất, đánh giá, báo cáo](#6-biển-đề-xuất-đánh-giá-báo-cáo)
7. [Dịch vụ nhận diện nội bộ](#7-dịch-vụ-nhận-diện-nội-bộ)
8. [Quản trị](#8-quản-trị)
9. [Tài liệu OpenAPI](#9-tài-liệu-openapi)

## 1. Quy ước chung

| Hạng mục | Quy ước |
| --- | --- |
| Tiền tố | `/api/v1` |
| Định dạng | JSON UTF-8, tên trường `camelCase`; tải ảnh bằng `multipart/form-data` |
| Xác thực | `Authorization: Bearer <token>`; endpoint "Công khai" không cần |
| Phân quyền | Cột **Quyền** ghi mã quyền ([CSDL §4](06-co-so-du-lieu.md#4-người-dùng-và-phân-quyền)); "Đăng nhập" là mọi tài khoản |
| Thời gian | ISO-8601 UTC, ví dụ `2026-01-15T08:30:12.345Z` |
| Toạ độ | `{ "lat": 21.0285, "lng": 105.8048 }`, WGS84 |
| Vùng | `bbox=minLng,minLat,maxLng,maxLat` |
| Phân trang | `page` (từ 0), `size` (≤ 100); trả `{ items, page, size, total }` |
| Chống gửi trùng | `clientId` (UUID do client sinh) trong đề xuất |
| Khoá lạc quan | Thao tác quản trị trên biển gửi `version` đọc được lần trước |
| Truy vết | `X-Request-Id`, trả lại trong response và lỗi |

## 2. Định dạng lỗi

```json
{
  "code": "VALIDATION_ERROR",
  "message": "Dữ liệu không hợp lệ",
  "details": [
    { "field": "location.lat", "issue": "phải nằm trong [-90, 90]" }
  ],
  "requestId": "7f3c2a1e-…"
}
```

| HTTP | `code` | Khi nào |
| --- | --- | --- |
| 400 | `VALIDATION_ERROR` | Dữ liệu đầu vào sai |
| 401 | `UNAUTHENTICATED` | Thiếu hoặc hết hạn phiên |
| 403 | `FORBIDDEN` | Không đủ quyền; tài khoản bị khoá |
| 404 | `NOT_FOUND` | Không có tài nguyên |
| 409 | `CONFLICT` | Trùng email, đã có báo cáo đang chờ |
| 409 | `SIGN_ALREADY_PROCESSED` | `version` không khớp: biển đã được người khác xử lý |
| 413 | `PAYLOAD_TOO_LARGE` | Ảnh > 10 MB |
| 415 | `UNSUPPORTED_MEDIA_TYPE` | Không phải JPEG, PNG, WebP |
| 422 | `BUSINESS_RULE` | Vi phạm quy tắc nghiệp vụ, ví dụ `labelMapVersion` không hỗ trợ, đánh giá biển của chính mình |
| 429 | `RATE_LIMITED` | Vượt giới hạn tần suất; có header `Retry-After` |
| 500 | `INTERNAL_ERROR` | Lỗi không lường trước |
| 503 | `RECOGNITION_UNAVAILABLE` | Dịch vụ nhận diện quá tải hoặc không phản hồi |

## 3. Tài khoản

| Method | Đường dẫn | Quyền | Mô tả | FR |
| --- | --- | --- | --- | --- |
| POST | `/auth/register` | Công khai | Đăng ký, vai trò `USER` | FR-01 |
| POST | `/auth/login` | Công khai | Đăng nhập, trả token | FR-01, FR-30 |
| POST | `/auth/logout` | Đăng nhập | Thu hồi token hiện tại | FR-01 |
| POST | `/auth/password/forgot` | Công khai | Gửi OTP qua email; luôn trả `202` | FR-13 |
| POST | `/auth/password/reset` | Công khai | Đặt lại mật khẩu bằng OTP | FR-13 |
| GET | `/me` | Đăng nhập | Thông tin tài khoản và danh sách quyền | FR-01 |
| PATCH | `/me` | Đăng nhập | Sửa tên hiển thị | FR-01 |
| PUT | `/me/password` | Đăng nhập | Đổi mật khẩu, thu hồi các phiên khác | FR-01 |

```http
POST /api/v1/auth/login
Content-Type: application/json

{ "email": "user@example.com", "password": "••••••••", "client": "MOBILE" }
```

```json
{
  "token": "q7Zr…",
  "expiresAt": "2026-03-14T08:30:12Z",
  "user": {
    "id": "4e0c…",
    "displayName": "Nguyễn Văn A",
    "role": "USER",
    "permissions": ["submission:create", "sign:rate", "report:create"]
  }
}
```

`GET /me` trả trực tiếp đối tượng `user` như trên, thêm trường `email`. Đối tượng này không được bọc trong `{ "user": … }`.

`client` là `MOBILE` hoặc `WEB`, quyết định thời hạn phiên. Web quản trị chỉ chấp nhận đăng nhập của tài khoản có `review:decide`. Sai mật khẩu quá 10 lần trong 15 phút theo email + IP thì trả `429`.

Ràng buộc đầu vào: email được trim và chuyển chữ thường, ≤ 255 ký tự. Mật khẩu mới dài 8–72 ký tự và không quá 72 byte UTF-8, vì BCrypt chỉ dùng 72 byte đầu. `displayName` được trim, dài 1–100 ký tự.

| Endpoint | Thành công | Lỗi riêng |
| --- | --- | --- |
| `POST /auth/register` `{ email, password, displayName }` | `201`, thân như `GET /me` | `409 CONFLICT` khi email đã đăng ký |
| `POST /auth/login` | `200` | `401` cùng một thông điệp dù email có tồn tại hay không; `403` khi tài khoản `LOCKED` hoặc `WEB` thiếu `review:decide`, chỉ sau khi mật khẩu đúng; `429` kèm `Retry-After` |
| `POST /auth/logout` | `204` | |
| `GET /me` | `200` | |
| `PATCH /me` `{ displayName }` | `200`, thân như `GET /me` | |
| `PUT /me/password` `{ currentPassword, newPassword }` | `204`; phiên đang dùng vẫn hợp lệ | `422 BUSINESS_RULE` khi sai mật khẩu hiện tại; `429` khi sai quá 10 lần trong 15 phút |

Token thiếu, sai hoặc đã thu hồi ở endpoint cần đăng nhập trả `401` kèm `WWW-Authenticate: Bearer`. Ở endpoint công khai, token như vậy bị bỏ qua và request được xử lý như khách. `expiresAt` tính tại lúc đăng nhập; mỗi lần dùng, phiên được gia hạn thêm một khoảng thời hạn đầy đủ.

## 4. Danh mục biển

| Method | Đường dẫn | Quyền | Mô tả | FR |
| --- | --- | --- | --- | --- |
| GET | `/sign-categories` | Công khai | Nhóm biển | FR-06, FR-08 |
| GET | `/sign-types` | Công khai | Toàn bộ loại biển đang dùng; hỗ trợ `If-None-Match` | FR-08 |
| GET | `/sign-types/{code}` | Công khai | Chi tiết một loại biển | FR-08 |

```json
{
  "labelMapVersion": 1,
  "items": [
    {
      "code": "P.127_50",
      "categoryCode": "PROHIBITORY",
      "name": "Tốc độ tối đa cho phép 50 km/h",
      "description": "Cấm các loại xe cơ giới chạy với tốc độ vượt quá 50 km/h…",
      "speedKmh": 50,
      "iconUrl": "/icons/P.127_50.png",
      "recognizable": true
    }
  ]
}
```

Ứng dụng tải danh mục một lần, lưu vào Room và chỉ tải lại khi ETag đổi.

Ba endpoint GET công khai của danh mục trả `Cache-Control: no-cache`. `GET /sign-types` trả thêm `ETag` mạnh (băm nội dung). Gửi `If-None-Match` khớp thì nhận `304` không có body. Danh sách chỉ gồm loại đang dùng. `labelMapVersion` là phiên bản danh mục nhãn lớn nhất trong các loại đó, `null` khi chưa nạp nhãn. `iconUrl` là `/icons/{icon_key}` hoặc `null`; `recognizable` là `true` khi loại có trong danh mục nhãn.

`GET /sign-types/{code}` trả một phần tử cùng dạng, `404 NOT_FOUND` nếu không có mã hoặc loại đã ngừng dùng.

`GET /sign-categories` trả `{ "items": [{ "code", "name", "description", "sortOrder" }] }`, sắp theo `sortOrder`.

## 5. Nhận diện

| Method | Đường dẫn | Quyền | Mô tả | FR |
| --- | --- | --- | --- | --- |
| GET | `/recognition/models` | Công khai | Mô hình có sẵn, mô hình mặc định, thẻ mô hình | FR-02, FR-37 |
| POST | `/recognition/detect` | Công khai, giới hạn tần suất | Nhận diện một ảnh | FR-02 |

```http
POST /api/v1/recognition/detect
Content-Type: multipart/form-data; boundary=…

image=<ảnh JPEG>
model=yolov8        # yolov5 | yolov8 | detr | bỏ trống = mặc định
```

```json
{
  "model": { "name": "yolov8", "version": "yolov8s-v1.0", "labelMapVersion": 1 },
  "image": { "width": 1280, "height": 960 },
  "inferenceMs": 142,
  "detections": [
    {
      "box": { "x": 812, "y": 233, "width": 64, "height": 66 },
      "signTypeCode": "P.123a",
      "confidence": 0.93,
      "name": "Cấm rẽ trái",
      "description": "Cấm các loại xe rẽ trái…",
      "iconUrl": "/icons/P.123a.png"
    }
  ]
}
```

Ứng dụng chuẩn hóa hướng ảnh trước khi tải lên. `image.width` và `image.height` là kích thước ảnh nhận từ ứng dụng. Nếu thu nhỏ ảnh để suy luận, backend quy đổi box về hệ tọa độ này trước khi trả kết quả. Gốc tọa độ nằm ở góc trên trái. Ứng dụng dùng ảnh đã tải lên để cắt. Giới hạn là 10 yêu cầu/phút mỗi tài khoản và 30/phút mỗi IP cho khách.

Toạ độ box là số thực theo pixel. `name`, `description`, `iconUrl` là `null` khi danh mục chưa có thông tin. Lỗi riêng của endpoint:

| HTTP | `code` | Khi nào |
| --- | --- | --- |
| 400 | `VALIDATION_ERROR` | Thiếu `image`, hoặc `model` không thuộc `yolov5`, `yolov8`, `detr` |
| 413 | `PAYLOAD_TOO_LARGE` | Ảnh lớn hơn 10 000 000 byte hoặc 25 triệu pixel |
| 415 | `UNSUPPORTED_MEDIA_TYPE` | Nội dung không phải JPEG, PNG, WebP, dù đuôi file là gì |
| 429 | `RATE_LIMITED` | Vượt giới hạn; `Retry-After` là số giây tới cửa sổ sau |
| 503 | `RECOGNITION_UNAVAILABLE` | Dịch vụ nhận diện quá tải, lỗi hoặc không trả lời trong 5 giây; `Retry-After: 1` |

## 6. Biển, đề xuất, đánh giá, báo cáo

| Method | Đường dẫn | Quyền | Mô tả | FR |
| --- | --- | --- | --- | --- |
| GET | `/signs?bbox=&categories=&signTypeCodes=&zoom=` | Công khai | Biển `APPROVED`, `UNDER_REVIEW` trong vùng, hoặc điểm gộp | FR-05, FR-06, FR-08 |
| GET | `/signs/{id}` | Công khai | Chi tiết biển; kèm đánh giá của tôi nếu đã đăng nhập | FR-07 |
| PUT | `/signs/{id}/rating` | `sign:rate` | Đánh giá `CONFIRM` / `DENY` | FR-09 |
| POST | `/signs/{id}/reports` | `report:create` | Báo cáo biển sai, multipart nếu có ảnh | FR-10 |
| POST | `/submissions` | `submission:create` | Gửi một đề xuất | FR-04, FR-12 |
| GET | `/me/submissions` | Đăng nhập | Đề xuất của tôi | FR-11 |
| GET | `/me/reports` | Đăng nhập | Báo cáo của tôi và kết quả | FR-10 |
| GET | `/places/search?q=` | Công khai | Tìm địa điểm, kết quả có toạ độ | FR-14 |

Chi tiết biển công khai áp dụng cùng trạng thái hiển thị như bản đồ. API trả `404` khi ID không tồn tại hoặc biển chưa công bố, đã bị từ chối hay đã gỡ.

### Biển trong vùng

```json
{
  "mode": "SIGNS",
  "items": [
    {
      "id": "c1a9…",
      "signTypeCode": "P.123a",
      "categoryCode": "PROHIBITORY",
      "location": { "lat": 21.0285, "lng": 105.8048 },
      "headingDeg": 92,
      "status": "APPROVED",
      "confidence": 0.91
    }
  ]
}
```

`categories` và `signTypeCodes` là các danh sách tuỳ chọn, phân cách bằng dấu phẩy. Nếu gửi cả hai, mỗi biển phải khớp cả nhóm lẫn mã. Khi còn hơn 500 biển sau khi lọc, API trả `"mode": "CLUSTERS"` với `items` gồm `{ location, count }` theo ô lưới.

Ô lưới là hình vuông theo độ, cạnh `360 / 2^zoom / 8`, với `zoom` là số nguyên từ 0 tới 22. `location` của một ô là trung bình toạ độ các biển trong ô. Tổng `count` của các ô bằng số biển khớp bộ lọc trong `bbox`.

### Gửi đề xuất

```http
POST /api/v1/submissions
Authorization: Bearer q7Zr…
Content-Type: multipart/form-data; boundary=…

metadata=<JSON bên dưới>
crop=<ảnh cắt JPEG, ≤ 200 KB>
```

```json
{
  "clientId": "0b8f6d2e-…",
  "signTypeCode": "P.123a",
  "confidence": 0.88,
  "location": { "lat": 21.0285, "lng": 105.8048 },
  "headingDeg": 92,
  "source": "CAMERA",
  "flags": [],
  "model": { "name": "yolov8n", "version": "yolov8n-v1.0", "labelMapVersion": 1 },
  "capturedAt": "2026-01-15T08:30:12.345Z"
}
```

`201` trả `{ id, status: "RECEIVED" }` sau khi ảnh đã lưu và bản ghi đã commit. Gửi lại cùng `clientId` trong cùng tài khoản trả `200` với đề xuất đã có, kể cả khi hai yêu cầu đến đồng thời. Hàng đợi trên ứng dụng gửi tuần tự từng đề xuất.

`metadata` là đối tượng JSON. Mã biển phải đang dùng và phiên bản danh mục trong `model.labelMapVersion` phải được hỗ trợ. Trường sai trả `400 VALIDATION_ERROR` kèm `details`, phiên bản chưa hỗ trợ trả `422 BUSINESS_RULE`. Ảnh được nhận dạng theo chữ ký JPEG. Ảnh quá 200 KiB trả `413 PAYLOAD_TOO_LARGE`, ảnh sai định dạng trả `415 UNSUPPORTED_MEDIA_TYPE`.

### Đề xuất của tôi

`GET /api/v1/me/submissions?page=0&size=20` chỉ trả đề xuất của tài khoản đang đăng nhập, theo `receivedAt` giảm dần. Kết quả theo phân trang ở §1. Mỗi mục gồm `id`, `signTypeCode`, `status` (`RECEIVED`, `GROUPED`, `ACCEPTED`, `REJECTED`), `rejectReason`, `signId`, `location`, `receivedAt` và `capturedAt`. `signId` và `rejectReason` có thể là `null`.

### Đánh giá và báo cáo

```json
PUT /api/v1/signs/{id}/rating
{ "value": "DENY" }
```

```json
POST /api/v1/signs/{id}/reports
{ "reason": "WRONG_TYPE", "description": "Biển là cấm rẽ phải" }
```

Báo cáo có ảnh dùng `multipart/form-data` với part `metadata` chứa cùng JSON và part `photo` chứa ảnh.

Đã có báo cáo đang chờ cho biển đó: `409 CONFLICT`. Người đã gửi đề xuất cho biển tự đánh giá: `422 BUSINESS_RULE`.

## 7. Dịch vụ nhận diện nội bộ

Chỉ backend gọi, trong mạng nội bộ Docker, không qua nginx.

| Method | Đường dẫn | Mô tả |
| --- | --- | --- |
| POST | `/v1/detect` | Multipart bắt buộc có `image`. Các trường `model`, `conf`, `iou` là tuỳ chọn. Kết quả gồm `detections` theo mã biển, `inferenceMs` và thông tin phiên bản |
| GET | `/v1/models` | `defaultModel` (họ mô hình mặc định) và danh sách mô hình đã nạp kèm thẻ mô hình |
| GET | `/healthz` | `200` khi cả ba mô hình đã nạp xong |

`model` nhận `yolov5`, `yolov8`, `detr` hoặc `modelVersion` của mô hình đã nạp.
Bỏ trường này để dùng mô hình mặc định trong cấu hình dịch vụ. `/v1/models` trả giá trị đó
trong `defaultModel`, nên backend đọc từ đây thay vì cấu hình lại. DETR bỏ qua `iou`.
Nếu ảnh còn thẻ EXIF Orientation, dịch vụ xoay ảnh theo thẻ trước khi suy luận và box tính theo
pixel của ảnh đã xoay. Ảnh đã chuẩn hóa hướng như §5 không bị đổi.

**Mở rộng về hàng chờ.** Hàng chờ đầy hoặc hết hạn chờ slot trả `503` kèm
`Retry-After: 1`. Backend chuyển lỗi thành `503 RECOGNITION_UNAVAILABLE`.
Dịch vụ bỏ yêu cầu hết hạn trước khi giải mã ảnh. Mặc định `SIGNA_QUEUE_TIMEOUT_S` là
4 giây để dịch vụ có thời gian trả lỗi trước timeout 5 giây của backend. Hạn này chỉ tính
thời gian chờ slot. Xem [cấu hình dịch vụ](../ai/README.md#cách-dịch-vụ-xử-lý-ảnh).

## 8. Quản trị

### Phê duyệt và biển

| Method | Đường dẫn | Quyền | Mô tả | FR |
| --- | --- | --- | --- | --- |
| GET | `/admin/review-queue?status=&suggestion=&categories=&bbox=` | `review:decide` | Biển `PENDING` / `UNDER_REVIEW`, sắp theo gợi ý rồi điểm | FR-31 |
| GET | `/admin/signs/{id}` | `review:decide` | Chi tiết đầy đủ: đề xuất thành viên, URL ảnh cắt, phân bố mã, biển gần, `version` | FR-31 |
| POST | `/admin/signs/{id}/approve` | `review:decide` | Duyệt; tuỳ chọn đổi `signTypeCode`, `location` | FR-31 |
| POST | `/admin/signs/{id}/reject` | `review:decide` | Từ chối, bắt buộc `reason` | FR-31 |
| POST | `/admin/signs/{id}/keep` | `review:decide` | Giữ biển `UNDER_REVIEW`; tuỳ chọn sửa mã, vị trí, hướng trong cùng thao tác | FR-31 |
| POST | `/admin/signs` | `sign:edit` | Thêm biển trực tiếp | FR-33 |
| PATCH | `/admin/signs/{id}` | `sign:edit` | Sửa mã, vị trí, hướng | FR-33 |
| POST | `/admin/signs/{id}/remove` | `sign:edit` | Gỡ biển, bắt buộc `reason` | FR-33 |

```json
POST /api/v1/admin/signs/{id}/approve
{
  "version": 7,
  "signTypeCode": "P.123b",
  "location": { "lat": 21.02851, "lng": 105.80473 }
}
```

`version` lệch: `409 SIGN_ALREADY_PROCESSED`, web tải lại biển và báo cho kiểm duyệt viên.

Mọi thao tác trên một biển đã có đều gửi `version`. Bảng dưới liệt kê nội dung request của từng thao tác:

| Thao tác | Nội dung |
| --- | --- |
| `approve` | `version`; tuỳ chọn `signTypeCode`, `location` |
| `reject`, `remove` | `version`, `reason` (1–1000 ký tự) |
| `keep` | `version`; tuỳ chọn `signTypeCode`, `location`, `headingDeg`. Có thay đổi thì sửa và giữ trong một giao dịch, ghi lịch sử `EDITED` rồi `KEPT` |
| `PATCH /admin/signs/{id}` | `version` và ít nhất một trong `signTypeCode`, `location`, `headingDeg` (`null` để xoá hướng) |
| `POST /admin/signs` | `signTypeCode`, `location`, `headingDeg` (có thể `null`). Trả `201` với `{ id, version }` |

Các thao tác ghi khác trả `204`.

**Hàng đợi.** `GET /admin/review-queue` trả trang `{ items, page, size, total }`. Mỗi mục có dạng:

```json
{
  "id": "c1a9…",
  "signTypeCode": "P.123a",
  "signName": "Cấm rẽ trái",
  "categoryCode": "PROHIBITORY",
  "location": { "lat": 21.0285, "lng": 105.8048 },
  "headingDeg": 92,
  "status": "PENDING",
  "confidence": 0.82,
  "suggestion": "APPROVE",
  "distinctSubmitters": 2,
  "flags": [],
  "createdAt": "2026-01-15T08:30:12.345Z"
}
```

**Chi tiết.** `GET /admin/signs/{id}` gồm mọi trường của mục hàng đợi, thêm các trường sau:

```json
{
  "version": 7,
  "locationLocked": false,
  "relatedSignId": null,
  "coverCropUrl": "https://…(URL ký 10 phút)",
  "confirmCount": 0,
  "denyCount": 0,
  "codeWeights": { "P.123a": 1.8, "P.123b": 0.4 },
  "submissions": [
    {
      "id": "0b8f…",
      "signTypeCode": "P.123a",
      "confidence": 0.9,
      "weight": 0.9,
      "source": "CAMERA",
      "flags": [],
      "headingDeg": 92,
      "location": { "lat": 21.02851, "lng": 105.80479 },
      "receivedAt": "2026-01-15T08:30:13.002Z",
      "status": "GROUPED",
      "cropUrl": "https://…(URL ký 10 phút)"
    }
  ],
  "nearbySigns": [
    { "id": "77d2…", "signTypeCode": "P.123b", "location": { "lat": 21.0286, "lng": 105.8049 }, "status": "APPROVED" }
  ]
}
```

`flags` của biển có thể chứa `DIFFERS_FROM_APPROVED`; khi đó `relatedSignId` trỏ tới biển đã duyệt mà biển này khác mã. `nearbySigns` gồm các biển `APPROVED` và `UNDER_REVIEW` trong bán kính 50 m.

`GET /admin/review-queue` nhận `status` (`PENDING` hoặc `UNDER_REVIEW`, bỏ trống thì lấy cả hai), `suggestion`, `categories` (danh sách mã nhóm cách nhau bởi dấu phẩy), `bbox` (như `GET /signs`), `page` tính từ 0 và `size` mặc định 20, tối đa 100. Thứ tự là "Nên duyệt" trước, rồi `confidence` giảm dần, rồi `createdAt` tăng dần, cuối cùng theo `id`.

`GET /admin/signs/{id}` trả biển ở mọi trạng thái, `404` nếu không có. `submissions` sắp theo `receivedAt` tăng dần, mỗi đề xuất có thêm `status`. `codeWeights` gồm cả mã có trọng số 0. `nearbySigns` không gồm chính biển đang xem, biển gần nhất đứng trước.

Trạng thái áp dụng của từng thao tác:

| Thao tác | Trạng thái biển | Kết quả |
| --- | --- | --- |
| `approve` | `PENDING` | `APPROVED`, `M = 2`, `suggestion = null`. Lịch sử `APPROVED` |
| `reject` | `PENDING` | `REJECTED`. Mọi đề xuất thành viên `REJECTED` với lý do của kiểm duyệt viên, cắt còn 500 ký tự. Lịch sử `REJECTED` |
| `keep` | `UNDER_REVIEW` | `APPROVED`, `M = 2`, `deny_since_review = 0`. Lịch sử `EDITED` nếu có sửa, rồi `KEPT` |
| `remove` | `APPROVED`, `UNDER_REVIEW` | `REMOVED`. Đề xuất `GROUPED` chuyển `REJECTED`. Lịch sử `REMOVED` |
| `PATCH` | `PENDING`, `APPROVED`, `UNDER_REVIEW` | Không đổi trạng thái. Lịch sử `EDITED`. Biển `PENDING` chỉ sửa được vị trí và hướng |
| `POST /admin/signs` | | `APPROVED`, `M = 2`, `locationLocked = true`. Lịch sử `CREATED` |

Khi kiểm duyệt viên chốt mã (duyệt, giữ, hoặc sửa mã biển đã công bố), đề xuất khác mã chuyển `REJECTED` và trọng số của các mã khác về 0. Biển vừa duyệt vì vậy có điểm không dưới 0,5. Thao tác có `location` đặt `locationLocked = true`. `headingDeg` trong `keep` để trống thì giữ hướng cũ. Lịch sử ghi `before`, `after` gồm các trường thay đổi trong `status`, `signTypeCode`, `categoryCode`, `location`, `headingDeg`, `locationLocked`, `confidence`.

Lỗi của các thao tác ghi:

| Mã | Khi nào |
| --- | --- |
| `400 VALIDATION_ERROR` | Thiếu `version`, `reason` rỗng hoặc dài quá 1000 ký tự, toạ độ hoặc hướng sai miền, `PATCH` không có thay đổi nào |
| `404 NOT_FOUND` | Không có biển |
| `409 SIGN_ALREADY_PROCESSED` | `version` lệch, hoặc biển không ở trạng thái cho phép thao tác |
| `422 BUSINESS_RULE` | Mã không có hoặc đã ngừng dùng; đổi mã biển `PENDING` bằng `PATCH`; `PATCH` có giá trị trùng hệt biển hiện tại |

### Báo cáo

| Method | Đường dẫn | Quyền | Mô tả | FR |
| --- | --- | --- | --- | --- |
| GET | `/admin/reports?status=&reason=` | `report:resolve` | Báo cáo, gom theo biển | FR-32 |
| POST | `/admin/reports/{id}/accept` | `report:resolve` | Chấp nhận kèm `action`: `EDIT` (với thay đổi) hoặc `REMOVE` | FR-32 |
| POST | `/admin/reports/{id}/dismiss` | `report:resolve` | Từ chối, bắt buộc `note` | FR-32 |

`GET /admin/reports` trả trang theo biển. `status` mặc định là `PENDING`. Mỗi mục có dạng:

```json
{
  "sign": {
    "id": "c1a9…",
    "signTypeCode": "P.123a",
    "signName": "Cấm rẽ trái",
    "location": { "lat": 21.0285, "lng": 105.8048 },
    "status": "APPROVED",
    "version": 7
  },
  "reports": [
    {
      "id": "5e21…",
      "reason": "WRONG_TYPE",
      "description": "Biển là cấm rẽ phải",
      "photoUrl": null,
      "reporterName": "Nguyễn Văn A",
      "createdAt": "2026-01-16T02:10:00.000Z",
      "status": "PENDING",
      "resolution": null,
      "resolutionNote": null
    }
  ]
}
```

| Thao tác | Nội dung |
| --- | --- |
| `accept` sửa biển | `{ "action": "EDIT", "version": 7, "changes": { "signTypeCode": "P.123b", "location": { … } } }`, cần ít nhất một thay đổi |
| `accept` gỡ biển | `{ "action": "REMOVE", "version": 7 }` |
| `dismiss` | `{ "note": "…" }`, 1–1000 ký tự |

Chấp nhận một báo cáo sẽ sửa hoặc gỡ biển và ghi lịch sử trong cùng giao dịch. Các báo cáo khác đang chờ của biển đó vẫn ở trạng thái chờ. `version` lệch thì trả `409 SIGN_ALREADY_PROCESSED`.

### Lịch sử, thống kê, so sánh mô hình

| Method | Đường dẫn | Quyền | Mô tả | FR |
| --- | --- | --- | --- | --- |
| GET | `/admin/audit-logs?entityType=&entityId=&actorId=&action=&from=&to=` | `audit:read` | Lịch sử xử lý | FR-34 |
| GET | `/admin/stats?from=&to=` | `stats:read` | Thống kê | FR-38 |
| POST | `/admin/recognition/compare` | `recognition:compare` | Một ảnh, trả kết quả của cả ba mô hình | FR-37 |

Mỗi dòng lịch sử có dạng `{ id, entityType, entityId, action, actorType, actorId, actorName, beforeValue, afterValue, reason, requestId, createdAt }`. `actorId` và `actorName` là `null` khi `actorType = SYSTEM`. `from` và `to` là thời điểm ISO-8601.

`/admin/stats` trả:

```json
{
  "submissionsByDay": [{ "date": "2026-01-15", "count": 42 }],
  "decisions": { "approved": 30, "rejected": 4 },
  "processingTime": { "averageMs": 5400000, "samples": 34 },
  "signsByCategory": [{ "categoryCode": "PROHIBITORY", "name": "Biển cấm", "count": 120 }],
  "densityCells": [{ "location": { "lat": 21.03, "lng": 105.80 }, "count": 18 }]
}
```

`processingTime` đo từ đề xuất đầu tiên của biển tới quyết định duyệt hoặc từ chối. `densityCells` dùng cùng ô lưới với bản đồ ở mức zoom 12.

`/admin/recognition/compare` nhận multipart `image` như `/recognition/detect`, rồi gọi lần lượt ba mô hình:

```json
{
  "image": { "width": 1280, "height": 960 },
  "results": [
    {
      "model": { "name": "yolov8", "version": "yolov8s-v1.0", "labelMapVersion": 1 },
      "inferenceMs": 142,
      "detections": [{ "box": { "x": 812, "y": 233, "width": 64, "height": 66 }, "signTypeCode": "P.123a", "confidence": 0.93 }]
    },
    { "model": { "name": "detr" }, "error": { "message": "Dịch vụ nhận diện quá tải" } }
  ]
}
```

Một mô hình lỗi không làm hỏng cả request; kết quả của mô hình đó mang `error`.

### Danh mục và người dùng

| Method | Đường dẫn | Quyền | Mô tả | FR |
| --- | --- | --- | --- | --- |
| GET | `/admin/sign-types?active=` | `catalog:manage` | Mọi loại biển, kể cả loại đã ngừng dùng | FR-35 |
| POST | `/admin/sign-categories` | `catalog:manage` | Thêm nhóm biển | FR-35 |
| PATCH | `/admin/sign-categories/{code}` | `catalog:manage` | Sửa nhóm biển | FR-35 |
| POST | `/admin/sign-types` | `catalog:manage` | Thêm loại biển chỉ nhập tay | FR-35 |
| PATCH | `/admin/sign-types/{code}` | `catalog:manage` | Sửa tên, ý nghĩa, tốc độ, nhóm, `active` | FR-35 |
| PUT | `/admin/sign-types/{code}/icon` | `catalog:manage` | Tải ảnh minh hoạ | FR-35 |
| GET | `/admin/users?q=&role=&status=` | `user:manage` | Danh sách người dùng | FR-36 |
| PATCH | `/admin/users/{id}` | `user:manage` | Đổi `role`, `status` | FR-36 |

`GET /admin/sign-types` trả `{ items: [...] }` với mỗi loại có dạng `{ code, categoryCode, name, description, speedKmh, iconUrl, recognizable, active, updatedAt }`. Dùng `active=true` hoặc `active=false` để lọc. Bỏ tham số để lấy tất cả. Ngừng dùng một loại bằng `PATCH` với `active=false`.

Tạo nhóm hoặc loại trả `201`, trùng mã trả `409 CONFLICT`. Mã nhóm được chuẩn hoá chữ hoa và phải khớp `^[A-Z][A-Z0-9_]{0,19}$`. Mã loại dài 1–30 ký tự sau khi bỏ khoảng trắng ở hai đầu. `POST /admin/sign-types` luôn tạo loại `recognizable = false`. Trong `PATCH`, trường bỏ qua giữ giá trị cũ. Gửi `description: null` hoặc `speedKmh: null` để xoá giá trị tương ứng.

Tải ảnh minh hoạ dùng multipart part `icon`. Backend kiểm tra chữ ký tệp và chấp nhận PNG, JPEG hoặc WebP tối đa 1 MB. Thành công trả `204`. Tệp vượt giới hạn trả `413 PAYLOAD_TOO_LARGE`, định dạng không hỗ trợ trả `415 UNSUPPORTED_MEDIA_TYPE`.

Mỗi thay đổi danh mục ghi lịch sử `SIGN_CATEGORY` hoặc `SIGN_TYPE` trong cùng giao dịch, với người thực hiện và giá trị trước, sau. Backend xoá `cache:sign-types` sau khi commit.

Người dùng trong danh sách có dạng `{ id, email, displayName, role, status, createdAt, lastLoginAt }`. `q` tìm theo một phần email. Quản trị viên tự hạ vai trò hoặc tự khoá chính mình thì nhận `422 BUSINESS_RULE`.

## 9. Tài liệu OpenAPI

- Backend sinh OpenAPI bằng springdoc tại `/api/v1/openapi.json`; Swagger UI chỉ bật ở môi trường `local`.
- Dịch vụ nhận diện sinh OpenAPI bằng FastAPI tại `/openapi.json` trong mạng nội bộ.
- Web quản trị sinh kiểu TypeScript từ OpenAPI của backend; ứng dụng di động viết tay model theo OpenAPI.
- Thay đổi API được cập nhật trong tài liệu này và OpenAPI trong cùng Pull Request.
