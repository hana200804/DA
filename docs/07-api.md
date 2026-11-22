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

`client` là `MOBILE` hoặc `WEB`, quyết định thời hạn phiên. Web quản trị chỉ chấp nhận đăng nhập của tài khoản có `review:decide`. Sai mật khẩu quá 10 lần trong 15 phút theo email + IP thì trả `429`.

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
| GET | `/v1/models` | Danh sách mô hình đã nạp và thẻ mô hình |
| GET | `/healthz` | `200` khi cả ba mô hình đã nạp xong |

`model` nhận `yolov5`, `yolov8`, `detr` hoặc `modelVersion` của mô hình đã nạp.
Bỏ trường này để dùng mô hình mặc định trong cấu hình dịch vụ. DETR bỏ qua `iou`.
Quá tải trả `503` kèm `Retry-After`. Backend chuyển thành `503 RECOGNITION_UNAVAILABLE`.
Timeout phía backend là 5 giây.

## 8. Quản trị

### Phê duyệt và biển

| Method | Đường dẫn | Quyền | Mô tả | FR |
| --- | --- | --- | --- | --- |
| GET | `/admin/review-queue?status=&suggestion=&categories=&bbox=` | `review:decide` | Biển `PENDING` / `UNDER_REVIEW`, sắp theo gợi ý rồi điểm | FR-31 |
| GET | `/admin/signs/{id}` | `review:decide` | Chi tiết đầy đủ: đề xuất thành viên, URL ảnh cắt, phân bố mã, biển gần, `version` | FR-31 |
| POST | `/admin/signs/{id}/approve` | `review:decide` | Duyệt; tuỳ chọn đổi `signTypeCode`, `location` | FR-31 |
| POST | `/admin/signs/{id}/reject` | `review:decide` | Từ chối, bắt buộc `reason` | FR-31 |
| POST | `/admin/signs/{id}/keep` | `review:decide` | Giữ biển `UNDER_REVIEW` | FR-31 |
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

### Báo cáo

| Method | Đường dẫn | Quyền | Mô tả | FR |
| --- | --- | --- | --- | --- |
| GET | `/admin/reports?status=&reason=` | `report:resolve` | Báo cáo, gom theo biển | FR-32 |
| POST | `/admin/reports/{id}/accept` | `report:resolve` | Chấp nhận kèm `action`: `EDIT` (với thay đổi) hoặc `REMOVE` | FR-32 |
| POST | `/admin/reports/{id}/dismiss` | `report:resolve` | Từ chối, bắt buộc `note` | FR-32 |

### Lịch sử, thống kê, so sánh mô hình

| Method | Đường dẫn | Quyền | Mô tả | FR |
| --- | --- | --- | --- | --- |
| GET | `/admin/audit-logs?entityType=&entityId=&actorId=&action=&from=&to=` | `audit:read` | Lịch sử xử lý | FR-34 |
| GET | `/admin/stats?from=&to=` | `stats:read` | Thống kê | FR-38 |
| POST | `/admin/recognition/compare` | `recognition:compare` | Một ảnh, trả kết quả của cả ba mô hình | FR-37 |

### Danh mục và người dùng

| Method | Đường dẫn | Quyền | Mô tả | FR |
| --- | --- | --- | --- | --- |
| POST | `/admin/sign-categories` | `catalog:manage` | Thêm nhóm biển | FR-35 |
| PATCH | `/admin/sign-categories/{code}` | `catalog:manage` | Sửa nhóm biển | FR-35 |
| POST | `/admin/sign-types` | `catalog:manage` | Thêm loại biển chỉ nhập tay | FR-35 |
| PATCH | `/admin/sign-types/{code}` | `catalog:manage` | Sửa tên, ý nghĩa, ảnh, `active` | FR-35 |
| PUT | `/admin/sign-types/{code}/icon` | `catalog:manage` | Tải ảnh minh hoạ | FR-35 |
| GET | `/admin/users?q=&role=&status=` | `user:manage` | Danh sách người dùng | FR-36 |
| PATCH | `/admin/users/{id}` | `user:manage` | Đổi `role`, `status` | FR-36 |

## 9. Tài liệu OpenAPI

- Backend sinh OpenAPI bằng springdoc tại `/api/v1/openapi.json`; Swagger UI chỉ bật ở môi trường `local`.
- Dịch vụ nhận diện sinh OpenAPI bằng FastAPI tại `/openapi.json` trong mạng nội bộ.
- Web quản trị sinh kiểu TypeScript từ OpenAPI của backend; ứng dụng di động viết tay model theo OpenAPI.
- Thay đổi API được cập nhật trong tài liệu này và OpenAPI trong cùng Pull Request.
