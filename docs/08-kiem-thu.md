# 08 · Kiểm thử và đánh giá

## Mục lục

1. [Chiến lược tổng thể](#1-chiến-lược-tổng-thể)
2. [Kiểm thử theo thành phần](#2-kiểm-thử-theo-thành-phần)
3. [Đánh giá và so sánh mô hình](#3-đánh-giá-và-so-sánh-mô-hình)
4. [Đánh giá trên thiết bị](#4-đánh-giá-trên-thiết-bị)
5. [Thử nghiệm thực địa](#5-thử-nghiệm-thực-địa)
6. [Đánh giá phê duyệt có trợ giúp](#6-đánh-giá-phê-duyệt-có-trợ-giúp)
7. [Kiểm thử bảo mật](#7-kiểm-thử-bảo-mật)
8. [Kiểm thử hiệu năng server](#8-kiểm-thử-hiệu-năng-server)
9. [Truy vết yêu cầu](#9-truy-vết-yêu-cầu)
10. [Tiêu chí hoàn thành và phân loại lỗi](#10-tiêu-chí-hoàn-thành-và-phân-loại-lỗi)

## 1. Chiến lược tổng thể

```text
              /  E2E  \            Ít: luồng ảnh → đề xuất → phê duyệt → bản đồ
             / Tích hợp \          Vừa: backend + MySQL, Redis, MinIO thật; dịch vụ nhận diện; TFLite
            /  Unit test  \        Nhiều: gom cụm, điểm tin cậy, gợi ý, phân quyền, tracker
           /  Đánh giá AI   \      Riêng: so sánh 3 mô hình, trên thiết bị, thực địa
```

Ưu tiên theo rủi ro với đề tài: **so sánh mô hình sai giao thức** làm mất giá trị phần nghiên cứu; **phê duyệt và lịch sử sai** làm mất giá trị phần hệ thống. Hai phần này được kiểm thử kỹ nhất.

| Thành phần | Unit | Tích hợp | E2E | Công cụ |
| --- | --- | --- | --- | --- |
| `mobile/` | Tracker, bỏ phiếu, NMS, cắt ảnh, ánh xạ nhãn | Mô hình TFLite trên ảnh mẫu, Room, hàng đợi gửi | Compose UI test màn hình chính | JUnit, Turbine, Compose UI Test |
| `backend/` | Gom cụm, điểm tin cậy, gợi ý, trạng thái biển, kiểm tra quyền | MySQL, Redis, MinIO thật bằng Testcontainers; dịch vụ nhận diện giả lập (WireMock); ArchUnit cho ranh giới module | Luồng đề xuất → phê duyệt | JUnit 5, AssertJ, Testcontainers, RestAssured |
| `ai/` | Chuyển đổi nhãn YOLO ↔ COCO, chia tập, manifest, bộ đánh giá | Dịch vụ nhận diện với mô hình nhỏ | — | pytest, httpx |
| `web-admin/` | Hàm định dạng, hook | Component với API giả lập | Đăng nhập → duyệt một biển | Vitest, Testing Library, MSW, Playwright |

| Phạm vi | Độ phủ dòng lệnh |
| --- | --- |
| Gom cụm, điểm tin cậy, gợi ý, tracker, bộ đánh giá mô hình | ≥ 90% |
| Phần còn lại của backend, dịch vụ nhận diện, logic ứng dụng | ≥ 70% |
| Giao diện | Không đặt chỉ tiêu %; kiểm thử theo luồng |

## 2. Kiểm thử theo thành phần

### 2.1. Ứng dụng di động

| ID | Đối tượng | Ca kiểm thử | Kết quả mong đợi |
| --- | --- | --- | --- |
| UT-M01 | Tracker | Phát hiện chỉ ở 1 khung | Không xác nhận, không sinh đề xuất |
| UT-M02 | Tracker | Phát hiện khớp 3 trong 5 khung | Track `Confirmed` |
| UT-M03 | Tracker | Track xác nhận rồi mất 11 khung | Kết thúc, sinh đúng 1 đề xuất |
| UT-M04 | Bỏ phiếu | Mã dẫn đầu chiếm 55% phiếu | Đề xuất có cờ `SPLIT_VOTE` |
| UT-M05 | Bỏ phiếu | Phát hiện xa mã A, gần mã B | Mã B thắng vì diện tích lớn hơn |
| UT-M06 | Cắt ảnh | Box sát mép ảnh, nới 10% | Không vượt biên, cạnh dài ≤ 256 px |
| UT-M07 | Đề xuất | Track kết thúc khi tốc độ 3 km/h | `headingDeg = null` |
| IT-M01 | TFLite | Chạy mô hình trên 50 ảnh mẫu có nhãn | Kết quả khớp bản Python (cùng box ± 2 px, cùng mã) trên ≥ 95% box; chạy được với GPU delegate và CPU |
| IT-M02 | Hàng đợi | Tắt mạng, sinh 20 đề xuất, bật mạng | Gửi đủ 20, không trùng |
| IT-M03 | Hàng đợi | Gửi thành công nhưng mất phản hồi, gửi lại | Server trả `200` cùng đề xuất, hàng đợi xoá mục |
| UI-M01 | Tra cứu | Tìm "re trai" (không dấu) | Có "Cấm rẽ trái" |
| UI-M02 | Bản đồ | Bật chế độ máy bay, mở tra cứu | Danh mục vẫn hiển thị |

### 2.2. Backend

| ID | Đối tượng | Ca kiểm thử | Kết quả mong đợi |
| --- | --- | --- | --- |
| UT-B01 | Gom cụm | 2 người, cùng mã, cách 8 m, cùng hướng, `p = 0,9` | Cùng một biển; điểm 0,82; gợi ý "Nên duyệt" |
| UT-B02 | Gom cụm | 1 người gửi 5 lần cùng biển trong 1 giờ | `distinct_submitters = 1`; chỉ lần đầu có trọng số |
| UT-B03 | Gom cụm | 2 đề xuất cùng mã, cách 5 m, hướng ngược | Hai biển khác nhau |
| UT-B04 | Gom cụm | Đề xuất có hướng, biển `PENDING` không hướng, cách 10 m | Gán vào biển đó |
| UT-B05 | Gom cụm | Đề xuất mã khác, cạnh biển `APPROVED` | Biển `PENDING` mới, cờ `DIFFERS_FROM_APPROVED` |
| UT-B06 | Gom cụm | Đề xuất cùng mã, cạnh biển `APPROVED` | Gán vào biển đó, không tạo biển mới |
| UT-B07 | Gợi ý | Mã thứ hai chiếm 35% trọng số | "Cần xem kỹ" |
| UT-B08 | Rà soát | Biển `APPROVED` nhận 3 đánh giá "không đúng" | `UNDER_REVIEW`, có dòng lịch sử `MARKED_UNDER_REVIEW` |
| UT-B09 | Tự duyệt | 3 người, `p = 0,95`, cấu hình bật | `APPROVED`, lịch sử ghi `AUTO_APPROVED`, `actor_type = SYSTEM` |
| UT-B10 | Tự duyệt | Như UT-B09, cấu hình tắt | Vẫn `PENDING` |
| UT-B11 | Phân quyền | Ma trận vai trò × endpoint quản trị | Đúng bảng quyền ở [CSDL §4](06-co-so-du-lieu.md#4-người-dùng-và-phân-quyền) |
| UT-B12 | Gom cụm | Gửi lại cùng mã của biển bị từ chối cách đây 15 ngày, với vị trí và hướng phù hợp | Gán đề xuất vào hồ sơ bị từ chối. Nếu mã khác hoặc đã quá 30 ngày, áp dụng luồng tạo biển thông thường |
| IT-B01 | Đề xuất | Gửi cùng `clientId` hai lần | Một bản ghi; lần hai trả `200` |
| IT-B02 | Đề xuất | Gửi đề xuất → chờ worker | Ảnh có trong `sign-crops`; đề xuất `GROUPED`; biển có trong DB |
| IT-B03 | Gom cụm đồng thời | Gửi song song 20 đề xuất cùng một biển từ 20 người | Đúng một biển |
| IT-B04 | Phê duyệt | Hai kiểm duyệt viên duyệt cùng `version` | Một thành công, một `409 SIGN_ALREADY_PROCESSED` |
| IT-B05 | Lịch sử | Duyệt, sửa, gỡ một biển | 3 dòng `audit_logs` đúng trước/sau; tài khoản backend không `UPDATE`/`DELETE` được bảng này |
| IT-B06 | Không gian | Biển biết trước trong và ngoài `bbox` | Chỉ biển trong được trả |
| IT-B07 | Không gian | `bbox` chứa 600 biển sau khi lọc theo mã | `mode = CLUSTERS`, tổng số đếm là 600 |
| IT-B08 | Nhận diện | Dịch vụ nhận diện giả lập trả chậm 6 giây | `503 RECOGNITION_UNAVAILABLE` sau 5 giây |
| IT-B09 | Nhận diện | Gửi ảnh nhận diện | Không có đối tượng mới nào trong MinIO |
| IT-B10 | Phiên | Gọi API sau khi đổi mật khẩu bằng phiên cũ | `401` |
| IT-B11 | Báo cáo | Báo cáo lần hai khi lần một đang chờ | `409 CONFLICT` |
| IT-B12 | Phục hồi đề xuất | MySQL commit nhưng Redis lỗi. Khôi phục Redis rồi giao lại cùng mục cho worker | Đề xuất rời `RECEIVED` sau lần quét. Mục giao lại không tăng trọng số hay thêm lịch sử trùng |
| IT-B13 | Tọa độ nhận diện | Ảnh gửi lớn hơn 1280 px, backend thu nhỏ trước khi gọi AI giả lập | Box trả về khớp vùng biển trên ảnh đã gửi. `image` trả kích thước trước khi thu nhỏ |
| IT-B14 | Đề xuất | Hai yêu cầu đồng thời từ cùng tài khoản với cùng `clientId` | Một phản hồi `201`, một phản hồi `200`, cùng ID. Ảnh của bản ghi thắng vẫn đọc được |

### 2.3. AI và dịch vụ nhận diện

| ID | Đối tượng | Ca kiểm thử | Kết quả mong đợi |
| --- | --- | --- | --- |
| UT-A01 | Chuyển nhãn | YOLO → COCO → YOLO | Giữ nguyên box (sai số làm tròn < 0,5 px) |
| UT-A02 | Chia tập | Hai khung cùng chuỗi ảnh | Cùng một tập |
| UT-A03 | Manifest | Sửa một ảnh rồi sinh lại | Chỉ hash của ảnh đó đổi |
| UT-A04 | Bộ đánh giá | Dự đoán trùng khớp nhãn | mAP = 1,0 |
| UT-A05 | Bộ đánh giá | Dự đoán của 3 adapter trên cùng ảnh mẫu | Cùng định dạng COCO, cùng `category_id` cho cùng mã |
| UT-A06 | Danh mục nhãn | `labels.yaml` có `id` trùng hoặc mã trùng | Kiểm tra báo lỗi |
| IT-A01 | Dịch vụ | `POST /v1/detect` với mỗi mô hình | Box theo toạ độ ảnh gốc, đúng `modelVersion`, `labelMapVersion` |
| IT-A02 | Dịch vụ | Gửi 20 yêu cầu DETR cùng lúc | Một phần trả `503`, không yêu cầu nào treo quá 10 giây |

### 2.4. Web quản trị

| ID | Đối tượng | Ca kiểm thử | Kết quả mong đợi |
| --- | --- | --- | --- |
| CT-W01 | Hàng đợi | API trả 3 biển với 3 gợi ý | Đúng thứ tự, đúng nhãn gợi ý |
| CT-W02 | Chi tiết biển | Biển có 2 mã cạnh tranh | Hiển thị phân bố mã và ảnh cắt từng đề xuất |
| CT-W03 | Phê duyệt | API trả `409` | Thông báo "biển đã được xử lý", tải lại |
| E2E-W01 | Luồng | Đăng nhập kiểm duyệt viên → duyệt một biển | Biển rời hàng đợi; lịch sử có dòng `APPROVED` |
| E2E-W02 | Phân quyền | `MODERATOR` mở trang người dùng | Bị chặn; API trả `403` |
| E2E-W03 | Phân quyền | Tài khoản `USER` đăng nhập web | Bị từ chối |

## 3. Đánh giá và so sánh mô hình

Khóa cấu hình chấm và ngưỡng từ val trước khi chấm ảnh test. Chốt cấu hình triển khai sau khi kiểm tra các chỉ tiêu chọn ở AI-04. Giữ cấu hình triển khai cố định khi nghiệm thu thực địa. [Thiết kế AI §4](04-thiet-ke-ai.md#4-giao-thức-đánh-giá-và-chọn-mô-hình)

| Chỉ số | Chỉ tiêu |
| --- | --- |
| mAP@0.5 tập test, mô hình server tốt nhất | ≥ 0,85 (NFR-05) |
| mAP@0.5 tập test, mô hình điện thoại (sau lượng tử hoá) | ≥ 0,80 (NFR-05) |
| Recall nhóm biển cấm trên ảnh test, trước tracker, tại ngưỡng đã khóa | ≥ 0,85 để chọn ứng viên điện thoại (NFR-06) |
| Mức giảm mAP do INT8 so với FP32 | < 2 điểm, nếu không thì dùng FP16 |
| mAP@0.5:0.95, AP theo kích thước, theo điều kiện, theo lớp, ma trận nhầm lẫn | Báo cáo cho cả 3 mô hình |
| Thời gian suy luận trên server tham chiếu | NFR-02 |

**Bảng kết quả chính của báo cáo:**

| Mô hình | Tham số | GFLOPs | mAP@0.5 | mAP@0.5:0.95 | AP nhỏ | mAP đêm | ms/ảnh CPU | ms/ảnh GPU | Giờ GPU huấn luyện |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| YOLOv5n | | | | | | | | | |
| YOLOv5s | | | | | | | | | |
| YOLOv8n | | | | | | | | | |
| YOLOv8s | | | | | | | | | |
| Biến thể DETR theo Q2 | | | | | | | | | |

Tính recall nhóm biển cấm trên ảnh test bằng số box thật ghép đúng mã ở IoU ≥ 0,5 chia tổng số box thật thuộc nhóm này. Đo cả biển tốc độ. Dùng giá trị để lọc ứng viên đã khóa và nghiệm thu chuỗi riêng ở §5.

**Thí nghiệm bổ sung (AI-07)** gồm ảnh 640 so với 960 và FP32 / FP16 / INT8 cho mô hình điện thoại. Đối chứng có và không có dữ liệu tổng hợp là hạng mục riêng ở [M3](09-hang-muc-cong-viec.md#m3--ba-mô-hình).

**Server tham chiếu:** máy chủ demo 4 vCPU, 8 GB RAM, không GPU; nếu có GPU thì báo cáo thêm cột GPU, ghi rõ loại GPU.

## 4. Đánh giá trên thiết bị

- **Thiết bị:** ít nhất hai điện thoại Android thuộc hai phân khúc ([Đặc tả · Q7](02-dac-ta-yeu-cau.md#11-vấn-đề-cần-làm-rõ)); máy tầm trung là thiết bị tham chiếu.
- **Đo lặp lại được:** chế độ phát lại đưa từng khung video vào chuỗi xử lý thay cho camera. Dùng video val khi chọn cấu hình và video thực địa khi nghiệm thu.

| Chỉ số | Cách đo | Chỉ tiêu |
| --- | --- | --- |
| FPS | Số khung xử lý xong chia thời gian, sau 1 phút khởi động | ≥ 15 (NFR-01) |
| Độ trễ từng bước | Đồng hồ đơn điệu quanh tiền xử lý, detector, tracker | Theo ngân sách [Thiết kế AI §5](04-thiet-ke-ai.md#5-nhận-diện-trên-thiết-bị) |
| Nhiệt | FPS sau 20 phút chạy liên tục | ≥ 10 |
| Kích thước mô hình | File trong `assets/` | < 15 MB (NFR-07) |
| Bộ nhớ | Android Profiler khi chạy 20 phút | Không tăng liên tục |

Đo mọi ứng viên bằng video val để chọn cấu hình. Đo lại cấu hình đã chọn trên video thực địa để báo cáo kết quả cuối. [Thiết kế AI §4](04-thiet-ke-ai.md#4-giao-thức-đánh-giá-và-chọn-mô-hình).

## 5. Thử nghiệm thực địa

Dùng video và nhãn chuẩn của tuyến ([Dữ liệu §4](05-du-lieu.md#4-video-thực-địa)).

| Chỉ số | Định nghĩa | Chỉ tiêu |
| --- | --- | --- |
| mAP@0.5 trên tập thực địa | Như §3, trên khung trích từ video | ≥ 0,75 (mô hình điện thoại) |
| Recall theo tuyến | Biển thật có ít nhất một đề xuất đúng mã / tổng biển thật nhìn được theo hướng đi | Báo cáo theo điều kiện |
| Recall nhóm biển cấm sau tracker | Biển cấm thật có ít nhất một đề xuất đúng mã sau tracker / tổng biển cấm thật nhìn được theo hướng đi. Tính cả biển tốc độ, mỗi biển tính một lần trong một lượt đi | ≥ 0,85 (nghiệm thu NFR-06) |
| Đề xuất thừa | Đề xuất không ứng với biển thật nào / tổng đề xuất | Báo cáo; so sánh có / không có tracker |
| Sai số vị trí đề xuất | Khoảng cách tới vị trí thật | Trung vị ≤ 15 m; báo cáo p90 |

## 6. Đánh giá phê duyệt có trợ giúp

Đo mục tiêu G4. Thuật toán: [Thiết kế AI §6](04-thiet-ke-ai.md#6-gom-cụm-và-điểm-tin-cậy).

Đề xuất sinh từ ít nhất 3 lượt đi trên mỗi tuyến thực địa. Mỗi lượt được gán một tài khoản khác nhau để mô phỏng nhiều người dùng. Dùng cùng đề xuất và nhãn chuẩn cho cả hai phương án. Tắt tự duyệt khi đo G4. Nếu triển khai FR-25, chạy thêm lượt riêng để đo precision của biển tự duyệt.

**Kịch bản:**

| Kịch bản | Cách tạo |
| --- | --- |
| Bình thường | Toàn bộ đề xuất thật |
| Nhiễu mô hình | Đổi ngẫu nhiên mã của 10% và 20% đề xuất sang mã cùng nhóm |
| Nhiễu GPS | Cộng sai số chuẩn σ = 5, 10, 15 m |
| Người dùng phá | Một tài khoản gửi đề xuất sai mã tại 20% số biển, mỗi biển nhiều lần |

**Chỉ số, so với phê duyệt từng đề xuất:**

| Chỉ số | Chỉ tiêu |
| --- | --- |
| Số quyết định của kiểm duyệt viên | Giảm ≥ 60% |
| Precision biển `APPROVED` (đúng mã, cách vị trí thật ≤ 15 m) | ≥ 0,95 |
| Recall biển thật sau 3 lượt | ≥ 0,70 |
| Gợi ý "Nên duyệt" trùng quyết định đúng | ≥ 90% |
| Precision của biển tự duyệt (khi bật FR-25) | ≥ 0,98 |
| Mức giảm precision ở kịch bản người dùng phá | Báo cáo |

Khi triển khai, đặt script mô phỏng trong `backend/src/test` để chạy lại với cùng dữ liệu.

## 7. Kiểm thử bảo mật

- [ ] Mọi API cần đăng nhập trả `401` khi thiếu token, token sai hoặc đã thu hồi
- [ ] Ma trận quyền UT-B11 đạt cho mọi endpoint quản trị
- [ ] Người dùng không đọc được đề xuất, báo cáo của người khác qua đổi ID
- [ ] Đầu vào sai kiểu, quá dài, toạ độ ngoài phạm vi bị từ chối với `400`; ảnh giả mạo đuôi bị `415`
- [ ] Đăng nhập, OTP, nhận diện ảnh bị giới hạn tần suất
- [ ] Mật khẩu lưu BCrypt; mật khẩu, token không xuất hiện trong log
- [ ] Ảnh trong `sign-crops`, `report-photos` không đọc được khi thiếu chữ ký
- [ ] Dịch vụ nhận diện, MySQL, Redis không truy cập được từ ngoài máy chủ
- [ ] CI quét bí mật trong mã nguồn ở mỗi Pull Request

## 8. Kiểm thử hiệu năng server

k6 trên môi trường Docker Compose giống bản demo.

| Kịch bản | Tải | Chỉ tiêu |
| --- | --- | --- |
| Biển theo vùng | 50 người dùng ảo, vùng 500 biển | p95 < 300 ms (NFR-03) |
| Gửi đề xuất | Tăng dần tới 50 đề xuất/giây | Tiếp nhận đủ theo NFR-04. Sau khi ngừng tải, hàng đợi phải xử lý hết đề xuất còn `RECEIVED`, không để mục chưa xác nhận |
| Nhận diện ảnh | 2 yêu cầu/giây với mỗi mô hình | NFR-02; quá tải trả `503` thay vì timeout |

## 9. Truy vết yêu cầu

Mỗi tiêu chí chấp nhận trong [Đặc tả §8](02-dac-ta-yeu-cau.md#8-tiêu-chí-chấp-nhận) có ít nhất một ca kiểm thử. Bảng cho các yêu cầu P0:

| Yêu cầu | Ca kiểm thử |
| --- | --- |
| FR-01 | IT-B10, §7 |
| FR-02 | IT-B08, IT-B09, IT-B13, IT-A01 |
| FR-03 | UT-M01 → UT-M05, IT-M01, §4 |
| FR-04 | UT-M06, UT-M07, IT-B01, IT-B02, IT-B12, IT-B14 |
| FR-05, FR-06 | IT-B06, IT-B07, UI test màn hình bản đồ |
| FR-07 | UI test chi tiết biển |
| FR-08 | UI-M01, UI-M02 |
| FR-09 | UT-B08, test API đánh giá |
| FR-10 | IT-B11 |
| FR-20 | UT-B01 → UT-B06, UT-B12, IT-B03, IT-B12 |
| FR-21 | UT-B01, UT-B07, §6 |
| FR-22 | UT-B08 |
| FR-23 | IT-B05 |
| FR-24 | IT-A01, IT-A02 |
| FR-30 | UT-B11, E2E-W03 |
| FR-31 | IT-B04, CT-W01 → CT-W03, E2E-W01 |
| FR-32 → FR-36 | Test API và E2E cho từng trang quản trị, E2E-W02 |
| AI-02, AI-03 | UT-A04, UT-A05, §3 |
| AI-04, AI-05 | §3, §4 |
| NFR-06 | §3, §5 |

## 10. Tiêu chí hoàn thành và phân loại lỗi

| Mức | Mô tả | Ví dụ |
| --- | --- | --- |
| S1 · Nghiêm trọng | Mất dữ liệu, sai quyền, hoặc ứng dụng dừng | Người dùng thường gọi được API duyệt; crash khi mở camera |
| S2 · Cao | Chức năng chính không dùng được | Không gửi được đề xuất; bản đồ không tải |
| S3 · Trung bình | Chạy nhưng sai một phần | Bộ lọc bản đồ không lưu |
| S4 · Thấp | Giao diện, chính tả | Lệch bố cục |

Điều kiện phát hành bản demo lên `main` và gắn tag:

- [ ] CI xanh trên mọi thành phần
- [ ] Ca kiểm thử của mọi yêu cầu P0 đạt
- [ ] Không còn lỗi S1, S2 đang mở
- [ ] Bảng kết quả §3 đủ cả ba mô hình
- [ ] Tài liệu trong `docs/` khớp với hành vi đã cài đặt
